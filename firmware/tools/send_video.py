"""Send one bounded Annex-B AVC test session; no USB configuration or firmware writes."""

import argparse
import ipaddress
import json
from pathlib import Path
import re
import socket
import struct
import time
import zlib


def send_video(host, port, data, width, height, fps):
    address = ipaddress.IPv4Address(host)
    if not any(
        address in ipaddress.IPv4Network(block)
        for block in ("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "127.0.0.0/8")
    ):
        raise ValueError("Use an explicit reachable private/loopback IPv4 address")
    if not 1024 <= port <= 65535:
        raise ValueError("Port must be in 1024..65535")
    if not (
        0 < width <= 1920
        and 0 < height <= 1080
        and not width % 2
        and not height % 2
        and 1 <= fps <= 60
    ):
        raise ValueError("Invalid encoding metadata")
    if not 1 <= len(data) <= 64 * 1024 * 1024:
        raise ValueError("Input limit")
    starts = list(re.finditer(b"\0\0(?:\0)?\1", data))
    aud = [m.start() for m in starts if m.end() < len(data) and data[m.end()] & 31 == 9]
    if not aud or aud[0] != 0 or len(aud) > 60:
        raise ValueError("Use 1..60 AUD-delimited Annex-B access units")
    frames = [data[a:b] for a, b in zip(aud, aud[1:] + [len(data)])]
    if any(len(frame) > 2 * 1024 * 1024 for frame in frames):
        raise ValueError("Frame limit")
    deadline = time.monotonic() + 30
    with socket.create_connection((str(address), port), timeout=5) as connection:

        def budget():
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise TimeoutError("Session deadline")
            connection.settimeout(min(5, remaining))

        def reply():
            raw = bytearray()
            while len(raw) <= 4096:
                budget()
                chunk = connection.recv(1)
                if chunk == b"\n":
                    result = json.loads(raw)
                    if not isinstance(result, dict) or result.get("ok") is not True:
                        raise ValueError("Receiver rejected request")
                    return result
                if not chunk:
                    raise ConnectionError("Receiver closed before reply")
                raw += chunk
            raise ValueError("Oversized reply")

        body = b'{"op":"stream"}'
        budget()
        connection.sendall(
            b"BVL1"
            + struct.pack(">I", len(body))
            + body
            + struct.pack(">4I", width, height, fps, 0)
        )
        greeting = reply()
        if greeting.get("ready") is not True or greeting.get("protocol") != "LHF2":
            raise ValueError("Receiver is not ready")
        for sequence, frame in enumerate(frames):
            key = any(
                m.end() < len(frame) and frame[m.end()] & 31 == 5
                for m in re.finditer(b"\0\0(?:\0)?\1", frame)
            )
            header = struct.pack(
                ">4s5I",
                b"LHF2",
                len(frame),
                sequence,
                sequence * 1000 // fps,
                int(key),
                zlib.crc32(frame),
            )
            budget()
            connection.sendall(header + frame)
            answer = reply()
            accepted = answer.get("accepted_sequence")
            if (
                type(accepted) is not int
                or accepted != sequence
                or (
                    "ack" in answer
                    and (type(answer["ack"]) is not int or answer["ack"] != sequence)
                )
            ):
                raise ValueError("Wrong frame receipt")
        budget()
        connection.sendall(struct.pack(">4s5I", b"LHF2", 0, 0xFFFFFFFF, 0, 0, 0))
        final = reply()
        if final.get("done") is not True or final.get("cleanup") is not True:
            raise ValueError("Receiver did not confirm EOS cleanup")
        for count_field in ("frames", "submitted"):
            if count_field in final and (
                type(final[count_field]) is not int or final[count_field] != len(frames)
            ):
                raise ValueError("Inconsistent completion count")
        return {
            "frames_sent": len(frames),
            "receiver": final,
            "vehicle_visual_verified": False,
        }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("host")
    parser.add_argument("input", type=Path)
    parser.add_argument("--port", type=int, default=47782)
    parser.add_argument("--width", type=int, required=True)
    parser.add_argument("--height", type=int, required=True)
    parser.add_argument("--fps", type=int, required=True)
    args = parser.parse_args()
    with args.input.open("rb") as stream:
        data = stream.read(64 * 1024 * 1024 + 1)
    print(
        json.dumps(
            send_video(args.host, args.port, data, args.width, args.height, args.fps),
            indent=2,
        )
    )


if __name__ == "__main__":
    main()
