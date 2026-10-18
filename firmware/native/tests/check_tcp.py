"""Loopback-only BVL1/LHF2 sessions and real H.264 decode through the native receiver."""

import argparse
from contextlib import contextmanager
import json
import os
from pathlib import Path
import re
import select
import shutil
import socket
import struct
import subprocess
import sys
import tempfile
import time
import zlib
from unittest.mock import patch

from check_real_video import require, require_clean_stderr, require_exit, run, units

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "tools"))
from send_video import send_video  # noqa: E402


def packet(payload, sequence, pts=None):
    # Read actual NAL headers, including encoders that use another nal_ref_idc.
    key = any(
        payload[m.end()] & 31 == 5
        for m in re.finditer(b"\x00\x00(?:\x00)?\x01", payload)
    )
    return (
        struct.pack(
            ">4sIIIII",
            b"LHF2",
            len(payload),
            sequence,
            sequence * 200 if pts is None else pts,
            int(key),
            zlib.crc32(payload),
        )
        + payload
    )


EOS = struct.pack(">4sIIIII", b"LHF2", 0, 0xFFFFFFFF, 0, 0, 0)
BODY = b'{"op":"stream"}'
HELLO = (
    b"BVL1" + struct.pack(">I", len(BODY)) + BODY + struct.pack(">IIII", 64, 32, 5, 0)
)


@contextmanager
def receiver(executable, output):
    process = subprocess.Popen(
        [str(executable), "0", str(output)],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    try:
        deadline = time.monotonic() + 5
        line = bytearray()
        while not line.endswith(b"\n"):
            remaining = deadline - time.monotonic()
            require(
                remaining > 0
                and len(line) < 4096
                and select.select([process.stdout], [], [], max(remaining, 0))[0],
                "Receiver startup line was incomplete or timed out",
            )
            part = os.read(process.stdout.fileno(), 4096 - len(line))
            require(part, "Receiver exited without a startup line")
            line.extend(part)
        listening = json.loads(line)
        require(listening["host"] == "127.0.0.1", "Receiver must stay on loopback")
        with socket.create_connection(
            ("127.0.0.1", listening["port"]), timeout=8
        ) as sock:
            with sock.makefile("rb") as reader:
                yield sock, reader, process
    finally:
        if process.poll() is None:
            process.kill()
        _, stderr = process.communicate(timeout=5)
        require_clean_stderr(stderr)


def finish(process, expected):
    stdout, stderr = process.communicate(timeout=5)
    require_exit(
        subprocess.CompletedProcess(process.args, process.returncode, stdout, stderr),
        expected,
        "Receiver did not exit normally",
    )


def response(reader):
    line = reader.readline(4097)
    require(line.endswith(b"\n") and len(line) <= 4096, "Missing or oversized response")
    result = json.loads(line)
    require(
        result["visual_verified"] is False, "Host must never claim vehicle visibility"
    )
    return result


def ready(sock, reader, hello=HELLO, fragmented=False):
    if fragmented:
        for value in hello:
            sock.sendall(bytes([value]))
    else:
        sock.sendall(hello)
    result = response(reader)
    require(
        result.get("ok") is True
        and result.get("ready") is True
        and result.get("protocol") == "LHF2"
        and result.get("backend") == "host-file"
        and result.get("decoded") is False,
        "Receiver did not accept the independent handshake",
    )


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("receiver", type=Path)
    args = parser.parse_args()
    executable = args.receiver.resolve()
    ffmpeg, ffprobe = shutil.which("ffmpeg"), shutil.which("ffprobe")
    if not ffmpeg or not ffprobe:
        parser.error("FFmpeg and ffprobe are required")
    with tempfile.TemporaryDirectory(prefix="openbyd-tcp-") as directory:
        root = Path(directory)
        source = root / "source.h264"
        run(
            ffmpeg,
            "-v",
            "error",
            "-f",
            "lavfi",
            "-i",
            "testsrc2=size=64x32:rate=5",
            "-frames:v",
            "10",
            "-c:v",
            "libx264",
            "-preset",
            "ultrafast",
            "-tune",
            "zerolatency",
            "-x264-params",
            "aud=1:repeat-headers=1:keyint=5:scenecut=0:bframes=0",
            "-f",
            "h264",
            str(source),
        )
        raw = source.read_bytes()
        frames = units(raw)
        require(len(frames) == 10, "Encoder fixture does not contain ten frames")
        output = root / "diagnostic-sender.h264"
        with receiver(executable, output) as (sock, reader, process):
            # Reuse this actual loopback connection; all wire I/O remains real.
            with patch("send_video.socket.create_connection", return_value=sock):
                sent = send_video("127.0.0.1", sock.getpeername()[1], raw, 64, 32, 5)
            require(
                sent["frames_sent"] == 10
                and sent["receiver"].get("termination") == "eos"
                and sent["receiver"].get("cleanup") is True
                and sent["vehicle_visual_verified"] is False,
                "Diagnostic sender did not complete the actual native TCP session",
            )
            finish(process, 0)
        require(output.read_bytes() == raw, "Diagnostic sender changed video bytes")
        for end in ("eos", "eof"):
            output = root / f"{end}.h264"
            with receiver(executable, output) as (sock, reader, process):
                ready(sock, reader, fragmented=True)
                for index, frame in enumerate(frames):
                    wire = packet(frame, index)
                    # Exercise reads that cannot assume a complete TCP header or AU.
                    for start in range(0, len(wire), 17):
                        sock.sendall(wire[start : start + 17])
                    ack = response(reader)
                    require(
                        ack.get("ok") is True
                        and ack.get("accepted_sequence") == index
                        and ack.get("decoded") is False,
                        "Missing accepted-frame acknowledgement",
                    )
                if end == "eos":
                    sock.sendall(EOS)
                sock.shutdown(socket.SHUT_WR)
                done = response(reader)
                require(
                    done.get("ok") is True
                    and done.get("done") is True
                    and done.get("cleanup") is True
                    and done.get("frames") == 10
                    and done.get("termination") == end
                    and done.get("decoded") is False,
                    "Incorrect completion report",
                )
                finish(process, 0)
            require(output.read_bytes() == raw, "TCP output differs from source")
        decoded = json.loads(
            run(
                ffprobe,
                "-v",
                "error",
                "-count_frames",
                "-select_streams",
                "v:0",
                "-show_entries",
                "stream=nb_read_frames,width,height",
                "-of",
                "json",
                str(root / "eos.h264"),
            ).stdout
        )["streams"][0]
        require(
            decoded == {"width": 64, "height": 32, "nb_read_frames": "10"},
            "TCP stream did not decode into the expected ten frames",
        )
        run(ffmpeg, "-v", "error", "-i", str(root / "eos.h264"), "-f", "null", "-")
        invalid_sessions = {
            "bad-handshake": (HELLO.replace(b"stream", b"foobar"), b""),
            "wrong-metadata": (HELLO[:-4] + struct.pack(">I", 1), b""),
            "truncated-handshake": (HELLO[:-1], b""),
            "bad-crc": (HELLO, packet(frames[0], 0)[:-1] + bytes([frames[0][-1] ^ 1])),
            "truncated-frame": (HELLO, packet(frames[0], 0)[:-1]),
            "oversized-frame": (
                HELLO,
                struct.pack(">4sIIIII", b"LHF2", 2097153, 0, 0, 1, 0),
            ),
            "truncated-eos": (HELLO, EOS[:-1]),
            "empty": (HELLO, b""),
        }
        for name, (hello, data) in invalid_sessions.items():
            output = root / f"{name}.h264"
            with receiver(executable, output) as (sock, reader, process):
                if hello == HELLO:
                    ready(sock, reader)
                else:
                    sock.sendall(hello)
                if data:
                    sock.sendall(data)
                sock.shutdown(socket.SHUT_WR)
                require(response(reader).get("ok") is False, f"Accepted {name}")
                finish(process, 1)
            require(not output.exists(), f"Left a partial output for {name}")
        for name, count in (("duplicate", 2), ("frame-limit", 61)):
            output = root / f"{name}.h264"
            with receiver(executable, output) as (sock, reader, process):
                ready(sock, reader)
                for index in range(count):
                    seq = 0 if name == "duplicate" else index
                    sock.sendall(packet(frames[0], seq))
                    ack = response(reader)
                    require(
                        ack.get("ok") is (index < count - 1), f"Wrong {name} result"
                    )
                finish(process, 1)
            require(not output.exists(), f"Left partial output for {name}")
        existing = root / "existing.h264"
        existing.write_bytes(b"preserved")
        with receiver(executable, existing) as (sock, reader, process):
            sock.sendall(HELLO)
            require(response(reader).get("ok") is False, "Replaced existing output")
            finish(process, 1)
        require(existing.read_bytes() == b"preserved", "Existing output changed")
        output = root / "timeout.h264"
        with receiver(executable, output) as (sock, reader, process):
            ready(sock, reader)
            sock.sendall(b"LHF")
            require(
                response(reader).get("ok") is False,
                "Partial-header timeout did not reject",
            )
            finish(process, 1)
        require(not output.exists(), "Timeout left partial output")
        print(
            json.dumps(
                {
                    "host_tcp_decoded_frames": 10,
                    "valid_terminations": ["eos", "eof"],
                    "rejected_sessions": len(invalid_sessions) + 4,
                    "qnx_tested": False,
                    "vehicle_visual_verified": False,
                }
            )
        )


if __name__ == "__main__":
    main()
