"""Real AVC through the native media engine; QNX APIs are host doubles."""

import json
from pathlib import Path
import re
import shutil
import sys
import tempfile

from check_media import Model, load
from check_real_video import require, run, units


def main():
    ffmpeg = shutil.which("ffmpeg")
    require(ffmpeg is not None, "FFmpeg with libx264 is required")
    library = load(sys.argv[1])
    with tempfile.TemporaryDirectory(prefix="obd-media-video-") as temporary:
        root = Path(temporary)
        source, output = root / "source.h264", root / "submitted.h264"
        run(
            ffmpeg,
            "-v",
            "error",
            "-f",
            "lavfi",
            "-i",
            "testsrc2=size=64x48:rate=5",
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
        frames = units(source.read_bytes())
        require(len(frames) == 10, "Expected ten real AVC frames")
        model = Model(library)
        require(model.open() == 0, f"Media initialization failed: {model.errors}")
        try:
            for sequence, frame in enumerate(frames):
                key = any(
                    frame[m.end()] & 31 == 5
                    for m in re.finditer(b"\0\0(?:\0)?\1", frame)
                )
                require(
                    model.submit(frame, sequence, sequence * 200, int(key)) == 0,
                    "Native submit rejected real AVC",
                )
            submitted = b"".join(
                payload for kind, payload in model.messages if kind == 1
            )
            require(submitted == source.read_bytes(), "IPC payload changed")
            output.write_bytes(submitted)
        finally:
            require(model.close() == 0, "Native cleanup failed")
        require(
            not model.errors and not model.objects,
            f"ABI model error/leak: {model.errors}",
        )

        def frame_hashes(path):
            result = run(
                ffmpeg,
                "-v",
                "error",
                "-i",
                str(path),
                "-vsync",
                "0",
                "-f",
                "framemd5",
                "-",
            ).stdout
            return [line for line in result.splitlines() if not line.startswith(b"#")]

        original, decoded = frame_hashes(source), frame_hashes(output)
        require(
            len(decoded) == 10 and original == decoded,
            f"Decoded pixels/count differ: original={len(original)}, submitted={len(decoded)}, matching={original == decoded}, first={decoded[:1]!r}",
        )
        print(
            json.dumps(
                {
                    "submitted_frames": model.media.submitted,
                    "ffmpeg_decoded_frames": 10,
                    "qnx_api_mocked": True,
                    "qnx_execution_verified": False,
                    "visual_verified": False,
                }
            )
        )


if __name__ == "__main__":
    main()
