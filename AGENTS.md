# OpenBYDDisplay development

Canonical repository: https://github.com/wayyoungboy/open-byd-display.
Chinese description: 比亚迪仪表显示与车机互联开源项目。

- Develop and distribute through GitHub PRs, Actions, Issues and Releases. Use feature branches after the initial import; do not publish unreviewed changes or silently replace release assets.
- Keep the self-developed version free of account, VIP and commercial-license gates. Preserve package signature, payload digest, target and hardware checks. Never imply that this removes another vendor's device-side authorization.
- Keep Song and Tang target profiles separate. A firmware filename, shared OS hash or version string is not proof of compatible instrument hardware.
- `instrument/` currently runs only on the host loopback interface and applies only to its sandbox. Do not turn it into a production flash backend, execute installer scripts, or access vehicle partitions without implementing and verifying the relevant platform contract.
- Do not add third-party APKs, firmware, decompiled code, vehicle identifiers, credentials or signing private keys. Preserve MIT attribution and NOTICE.
- Keep the test-version/risk notice visible in the app and README, with the applicable-law limitation. Do not promise zero legal liability, zero risk, real-car success, power-loss recovery or QNX compatibility without evidence.
- Run meaningful Kotlin/Python tests and relevant build/lint checks. Review code changes before publishing. Distinguish host laboratory tests from emulator, QNX and vehicle tests.
- Read `docs/update-protocol.md`, `docs/instrument-lab.md` and `docs/github-distribution.md` before modifying update transport, installation semantics or release workflows.
