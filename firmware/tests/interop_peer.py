"""Ephemeral loopback peer for the Kotlin/Python interoperability test."""

from pathlib import Path
import sys

from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey

from openbyddisplay.packages import build
from openbyddisplay.service import Laboratory, Server, atomic_json


def main() -> None:
    root = Path(sys.argv[1])
    source = root / "source"
    source.mkdir()
    (source / "version.txt").write_text("OpenBYDDisplay interop fixture")
    key = Ed25519PrivateKey.generate()
    for target in ("song-plus-dmi-2021", "tang-plus-dmi-2021"):
        (root / (target + ".fup")).write_bytes(
            build(source, key, target, "host-lab", "0.1.0")
        )
    lab = Laboratory(root / "state", key.public_key(), "song-plus-dmi-2021")
    with Server(0, lab) as server:
        atomic_json(root / "ready.json", {"port": server.server_address[1]})
        server.serve_forever()


if __name__ == "__main__":
    main()
