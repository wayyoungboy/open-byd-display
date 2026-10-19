"""Exercise the exact kit sample generator, native unpacker and host decoder."""
from pathlib import Path
import subprocess
import sys
import tempfile

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "tools"))
from make_preflight_kit import create_sample, frame_hashes  # noqa: E402


def require(value, detail):
    if not value:
        raise RuntimeError(detail)


with tempfile.TemporaryDirectory(prefix="obd-kit-sample-") as tmp:
    root = Path(tmp)
    samples = root / "samples"
    create_sample(samples)
    stream = (samples / "test-64x48-5fps-10frames.bvl1").read_bytes()
    (root / "frames.lhf2").write_bytes(stream[39:])
    output = root / "output.h264"
    subprocess.run([sys.argv[1], str(root / "frames.lhf2"), str(output)], check=True, timeout=15)
    require(output.read_bytes() == (samples / "test-64x48-5fps-10frames.h264").read_bytes(), "AVC bytes changed")
    subprocess.run(["ffmpeg", "-v", "error", "-i", str(output), "-vsync", "0", "-f", "framemd5", str(root / "output.md5")], check=True, timeout=15)
    require(frame_hashes((root / "output.md5").read_text()) == frame_hashes((samples / "reference.framemd5").read_text()), "Decoded frames changed")
