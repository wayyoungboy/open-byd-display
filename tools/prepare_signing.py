"""Decode the dedicated Actions secret without printing private material."""

import base64
import os
from pathlib import Path


def main() -> None:
    encoded = os.environ.get("ANDROID_KEYSTORE_BASE64", "")
    if not encoded:
        raise SystemExit("Dedicated Android signing secret is missing; release refused")
    target = Path(os.environ["RUNNER_TEMP"]) / "openbyddisplay-release.p12"
    with target.open("xb") as output:
        target.chmod(0o600)
        output.write(base64.b64decode(encoded, validate=True))


if __name__ == "__main__":
    main()
