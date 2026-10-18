"""Produce APK distribution metadata only; never invent a flashable firmware asset."""

import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess


def main() -> None:
    root = Path(__file__).resolve().parents[1]
    config = (root / "app/build.gradle.kts").read_text()
    version = re.search(r'versionName = "([0-9]+\.[0-9]+\.[0-9]+)"', config).group(1)
    if os.environ.get("GITHUB_REF_NAME") != "v" + version:
        raise SystemExit("Tag must match Android versionName")
    sdk = Path(os.environ["ANDROID_HOME"])
    source = root / "app/build/outputs/apk/release/app-release.apk"
    verification = subprocess.run(
        [
            str(sdk / "build-tools/34.0.0/apksigner"),
            "verify",
            "--print-certs",
            str(source),
        ],
        check=True,
        text=True,
        capture_output=True,
    ).stdout
    expected_signer = (
        (root / "config/android-release-certificate.sha256").read_text().strip()
    )
    if f"Signer #1 certificate SHA-256 digest: {expected_signer}" not in verification:
        raise SystemExit("Unexpected Android signing certificate; release refused")
    output = root / "artifacts"
    output.mkdir(exist_ok=False)
    name = f"OpenBYDDisplay-{version}-test.apk"
    target = output / name
    shutil.copyfile(source, target)
    digest = hashlib.sha256(target.read_bytes()).hexdigest()
    (output / "SHA256SUMS.txt").write_text(f"{digest}  {name}\n")
    manifest = {
        "project": "OpenBYDDisplay",
        "version": version,
        "commit": os.environ["GITHUB_SHA"],
        "test_release": True,
        "apk": {
            "name": name,
            "sha256": digest,
            "bytes": target.stat().st_size,
            "signer_sha256": expected_signer,
        },
        "flashable_instrument_firmware": [],
        "vehicle_validation_complete": False,
    }
    (output / "release-manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n"
    )


if __name__ == "__main__":
    main()
