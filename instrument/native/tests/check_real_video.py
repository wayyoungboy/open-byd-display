"""Generate real AVC, frame it as LHF2, validate with native C and decode with FFmpeg."""

import argparse
import json
from pathlib import Path
import re
import shutil
import struct
import subprocess
import tempfile
import zlib


def run(*args):
    result = subprocess.run(args, capture_output=True, timeout=45)
    require_exit(result, 0, "Command failed")
    return result


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def require_clean_stderr(stderr):
    require(
        not re.search(rb"Sanitizer|runtime error:", stderr),
        f"Sanitizer diagnostic: {stderr.decode(errors='replace')[:4096]}",
    )


def require_exit(result, expected, message):
    require_clean_stderr(result.stderr)
    require(
        result.returncode == expected,
        f"{message}: exit={result.returncode}, expected={expected}; "
        f"stderr={result.stderr.decode(errors='replace')[:4096]}",
    )


def units(data):
    starts = list(re.finditer(b"\x00\x00(?:\x00)?\x01", data))
    aud = [m.start() for m in starts if data[m.end()] & 31 == 9]
    if not aud or aud[0] != 0:
        raise ValueError("Encoder fixture needs AUD at the start of every access unit")
    return [data[a:b] for a, b in zip(aud, aud[1:] + [len(data)])]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("unpacker", type=Path)
    args = parser.parse_args()
    executable = str(args.unpacker.resolve())
    ffmpeg, ffprobe = shutil.which("ffmpeg"), shutil.which("ffprobe")
    if not ffmpeg or not ffprobe:
        parser.error("FFmpeg and ffprobe are required for the real decode check")
    with tempfile.TemporaryDirectory(prefix="openbyd-video-") as directory:
        root = Path(directory)
        source, framed, output = (
            root / "source.h264",
            root / "input.lhf2",
            root / "output.h264",
        )
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
        require(len(frames) == 10, "Encoder did not produce ten access units")
        wire = bytearray()
        for index, frame in enumerate(frames):
            key = any(
                frame[m.end()] & 31 == 5
                for m in re.finditer(b"\x00\x00(?:\x00)?\x01", frame)
            )
            wire += (
                struct.pack(
                    ">4sIIIII",
                    b"LHF2",
                    len(frame),
                    index,
                    index * 200,
                    int(key),
                    zlib.crc32(frame),
                )
                + frame
            )
        wire += struct.pack(">4sIIIII", b"LHF2", 0, 0xFFFFFFFF, 0, 0, 0)
        framed.write_bytes(wire)
        result = json.loads(run(executable, str(framed), str(output)).stdout)
        expected = {
            "frames": 10,
            "termination": "eos",
            "decoded": False,
            "visual_verified": False,
        }
        require(result == expected, "Unpacker reported an unexpected result")
        require(output.read_bytes() == raw, "Unpacked AVC differs from encoder output")
        # Ordinary clients may close without EOS, but only at a complete frame boundary.
        framed.write_bytes(wire[:-24])
        eof_output = root / "eof.h264"
        eof_result = json.loads(run(executable, str(framed), str(eof_output)).stdout)
        require(
            eof_result == {**expected, "termination": "eof"}
            and eof_output.read_bytes() == raw,
            "Complete-frame EOF was not preserved or labelled correctly",
        )
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
                str(output),
            ).stdout
        )["streams"][0]
        require(
            decoded == {"width": 64, "height": 32, "nb_read_frames": "10"},
            "Full decoder did not receive the expected ten 64x32 frames",
        )
        # Full decoder pass, in addition to ffprobe's counted frame output.
        run(ffmpeg, "-v", "error", "-i", str(output), "-f", "null", "-")
        for invalid in (
            wire[:-1],
            wire[:23],
            wire[:30] + bytes([wire[30] ^ 1]) + wire[31:],
        ):
            framed.write_bytes(invalid)
            destination = root / "rejected.h264"
            rejected = subprocess.run(
                [executable, str(framed), str(destination)],
                capture_output=True,
                timeout=10,
            )
            require_exit(rejected, 1, "Invalid input did not fail normally")
            require(not destination.exists(), "Invalid input left a partial output")
        # An existing output is never removed or overwritten, including on failure.
        framed.write_bytes(wire)
        rejected = subprocess.run(
            [executable, str(framed), str(output)], capture_output=True, timeout=10
        )
        require_exit(rejected, 2, "Existing output did not reject normally")
        require(
            output.read_bytes() == raw, "Existing output was overwritten or deleted"
        )
        print(
            json.dumps(
                {
                    "native_validated_access_units": 10,
                    "ffmpeg_decoded_frames": 10,
                    "dimensions": [64, 32],
                    "qnx_tested": False,
                    "vehicle_visual_verified": False,
                }
            )
        )


if __name__ == "__main__":
    main()
