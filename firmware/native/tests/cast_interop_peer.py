"""Kotlin-to-C socket peer with real AVC; QNX APIs remain explicit host doubles."""

import ctypes as C
import json
from pathlib import Path
import shutil
import socket
import subprocess
import sys

from check_real_video import run, units
from check_stream import RenderModel, stream_library


def main():
    root = Path(sys.argv[1])
    native = Path(__file__).resolve().parents[1]
    library_path = root / "model.so"
    command = [
        shutil.which("cc") or "cc",
        "-std=c99",
        "-dynamiclib" if sys.platform == "darwin" else "-shared",
        "-fPIC",
        "-DOBD_IO_HOST",
        "-I" + str(native / "core"),
        "-I" + str(native / "qnx66"),
    ]
    command += [
        str(native / path)
        for path in ("qnx66/media.c", "qnx66/stream.c", "qnx66/io.c", "core/lhf2.c")
    ]
    subprocess.run(
        command + ["-o", str(library_path)], check=True, capture_output=True, timeout=30
    )
    library = stream_library(library_path)
    ffmpeg = shutil.which("ffmpeg")
    if not ffmpeg:
        raise RuntimeError("FFmpeg with libx264 required for native cast interop")
    original = root / "source.h264"
    run(
        ffmpeg,
        "-v",
        "error",
        "-f",
        "lavfi",
        "-i",
        "testsrc2=size=64x48:rate=5",
        "-frames:v",
        "70",
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
        str(original),
    )
    frames = units(original.read_bytes())
    if len(frames) != 70:
        raise RuntimeError("Expected seventy AVC access units")
    for i, frame in enumerate(frames):
        (root / f"{i:03}.h264").write_bytes(frame)
    models = []
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        listener.listen(1)
        listener.settimeout(30)
        (root / "ready.json").write_text(
            json.dumps({"port": listener.getsockname()[1]})
        )
        for _ in range(2):  # INFO, then one continuous video session.
            connection, _ = listener.accept()
            with connection:
                connection.setblocking(False)
                model = RenderModel(library)
                cancelled = C.c_int(0)
                result = library.obd_stream_run(
                    C.byref(model.media),
                    C.byref(model.api),
                    C.byref(model.config),
                    connection.fileno(),
                    connection.fileno(),
                    1,
                    C.byref(cancelled),
                )
                if result or model.errors or model.objects:
                    raise RuntimeError(
                        f"C stream result={result}, model errors={model.errors}, objects={model.objects}"
                    )
                models.append(model)
    submitted = b"".join(payload for kind, payload in models[1].messages if kind == 1)
    (root / "submitted.h264").write_bytes(submitted)

    def pixels(path):
        output = run(
            ffmpeg, "-v", "error", "-i", str(path), "-vsync", "0", "-f", "framemd5", "-"
        ).stdout
        return [line for line in output.splitlines() if not line.startswith(b"#")]

    a, b = pixels(original), pixels(root / "submitted.h264")
    if len(b) != 70 or a != b:
        raise RuntimeError("Submitted AVC pixel hashes differ")
    (root / "evidence.json").write_text(
        json.dumps(
            {
                "submitted": 70,
                "decoded": 70,
                "matching_pixels": True,
                "qnx_api_mocked": True,
                "qnx_execution_verified": False,
                "visual_verified": False,
            }
        )
    )


if __name__ == "__main__":
    main()
