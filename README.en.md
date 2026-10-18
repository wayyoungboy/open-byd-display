[简体中文](README.md) | **English**

# OpenBYDDisplay

**Open-source instrument-cluster display and head-unit connectivity for BYD vehicles.**
比亚迪仪表显示与车机互联开源项目。

[![CI](https://github.com/wayyoungboy/open-byd-display/actions/workflows/ci.yml/badge.svg)](https://github.com/wayyoungboy/open-byd-display/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
![Status: test version](https://img.shields.io/badge/status-test%20version-orange)

> [!WARNING]
> **This is a test version, developed by an individual as a hobby project. By using this software you acknowledge and voluntarily accept the risks of testing it. To the maximum extent permitted by applicable law, the software is provided "as is", without warranty of any kind. The authors, contributors, and related individuals or organizations are not liable for any problems or resulting losses.**
>
> 本项目为测试版本，由个人出于兴趣爱好开发。使用本软件即表示你知悉并自愿承担测试风险。在法律允许的最大范围内，本软件按现状提供，不作任何保证；项目作者、贡献者及相关个人或组织不对可能存在的问题及由此产生的损失承担责任。

Everything goes through GitHub: [Releases](https://github.com/wayyoungboy/open-byd-display/releases) (APKs and future update packages) · [Issues](https://github.com/wayyoungboy/open-byd-display/issues) (bug reports and vehicle adaptation notes) · [Actions](https://github.com/wayyoungboy/open-byd-display/actions) (builds and tests).

---

## Overview

Several BYD models, such as the **2021 BYD Song PLUS DM-i**, ship with a digital instrument cluster that cannot natively mirror content from the head unit. The long-term goal of OpenBYDDisplay is to bring head-unit content to that cluster (and the floating "fly-screen" area): turn-by-turn navigation from AMap (高德地图), synced lyrics from NetEase Cloud Music (网易云音乐), and similar widgets.

OpenBYDDisplay is the open, account-free successor of the *Xiaohuihui head-unit tool* (小灰灰车机工具, a.k.a. PandaCast), a cluster-cast client that talks to the cluster over **USB NCM**. The underlying approach of that tool — an H.264 stream at **1920×720**, a fly-screen mode and a soft vignette / gradient edge mask — is the reference design for this project. Those casting components have **not** been released in this repository yet.

### What this repository contains today (v0.1.0)

v0.1.0 is an **update-pipeline research release**. It provides the plumbing needed to deliver software to the cluster safely, which every later feature depends on:

| Component | Path | Purpose |
| --- | --- | --- |
| Android head-unit app | `app/` | Picks, pre-checks, streams, queries, applies and cleans up cluster update jobs |
| Host lab service | `instrument/` | A Python reference implementation of the cluster-side update service, bound to `127.0.0.1` only, operating on a sandbox directory |
| FUP2 packaging tool | `instrument/openbyddisplay/packages.py` | Generates Ed25519 keys and builds signed, target-bound FUP2 component packages |
| Release tooling | `tools/`, `.github/workflows/` | CI, signed-APK release pipeline and release-asset generation |

> [!IMPORTANT]
> **There is currently no self-developed QNX firmware that can be flashed to a real car, and the new app has not passed an on-vehicle display acceptance test.** The `instrument/` service is a laboratory program, not a cluster image. It never runs a package's `install.sh`, never writes vehicle partitions and never touches boot records. Cast receiving/rendering on the cluster, a native QNX update backend and a full-vehicle recovery flow are still under development.

## Features

Implemented in this repository:

- **No login, VIP tier, commercial license or token.** The wire encoder refuses to send `token` / `client_time` fields.
- **USB NCM transport** — newline-delimited JSON over TCP (default port `47785`). When the cluster address is in `192.168.77.x`, the client binds to the head unit's NCM address `192.168.77.2`; the default cluster host is `192.168.77.1`.
- **Local package pre-check** before anything is sent:
  - **ZIP firmware packages:** exactly the six required files (`app.sv`, `os.sv`, `mcu.sv`, `kzbVersion`, `RcAppVersion`, `version_svp`) plus optional `patch.sv`, path sanitisation, ZIP CRC and the MD5 of each SV compressed body.
  - **FUP2 component packages:** header, manifest, paths and per-file payload SHA-256.
- **Streaming upload** with progress, a 30-second stall watchdog and no whole-package buffering in memory.
- **Upload and install are separate, explicit user actions.** Install requires ticking *"I have parked and verified the vehicle model and cluster hardware"*; the app sends the confirmation word `INSTALL_COMPONENTS` or `FLASH_FIRMWARE`.
- **Safe-by-default job handling:** the job state is persisted, the "apply attempted" flag is saved *before* the request is sent, uncertain results are only ever queried and never re-submitted automatically, and a corrupted local job record blocks new jobs instead of being overwritten.
- **Pre-flight cluster checks:** reads `update.info` capabilities and requires `cast.status` to report the cluster idle (not casting) before uploading.
- **No boot receiver and no automatic flashing.**
- **Lab service features:** Ed25519 signature verification, payload digests, target and hardware-ID binding, downgrade refusal, immutable sandbox generations with atomic `current.json` publishing, and regression tests for interrupted transfers, lost final replies, duplicate requests and partial publishes.

Signature, digest, target-model and hardware checks are intentionally kept: they prevent tampering and mis-flashing. This project does **not** remove any third-party firmware's own authorization or trusted public keys; packages signed with your own key only work with a service that trusts that key.

**Not yet implemented here:** cluster-side H.264 receiving/rendering, navigation and lyrics casting, fly-screen layout, vignette mask, flashable QNX firmware, automatic OTA.

## Supported vehicles and requirements

Each target has its own profile in [`instrument/targets.json`](instrument/targets.json). The two targets are **not interchangeable** — a matching firmware filename, OS hash or version string is not proof of compatible cluster hardware.

| Target ID | Vehicle | Status |
| --- | --- | --- |
| `song-plus-dmi-2021` | 2021 BYD Song PLUS DM-i | Profile created; cluster hardware, native QNX build and on-vehicle flashing **not yet verified** |
| `tang-plus-dmi-2021` | 2021 BYD Tang PLUS DM-i (name as reported by users) | Profile created; exact model and cluster part number **to be confirmed**; never cross-flash with the Song build |

**Head-unit app**

- Android 9 (API 28) or later; `targetSdk` 29
- USB connection to the cluster in NCM mode
- Package name `org.openbyddisplay` — independent from the original Xiaohuihui app, so both can be installed side by side; you do not need to uninstall or clear the original app

**Development**

- JDK 17
- Android SDK Platform 34 and Build-Tools 34.0.0 (Android Gradle Plugin 8.2.2, Kotlin 1.9.22, via the bundled Gradle wrapper)
- Python 3.12 (dependency: `cryptography`, see [`instrument/requirements.txt`](instrument/requirements.txt))

## Build and install

### Install a release build

1. Download the APK marked as a test build (`OpenBYDDisplay-<version>-test.apk`) from [Releases](https://github.com/wayyoungboy/open-byd-display/releases) and check it against the published SHA-256.
2. Install it on the head unit (sideload / `adb install`).

Development artifacts from CI are signed differently from release APKs; use the Release APK for day-to-day use.

### Build from source

Create your own `local.properties` (pointing at your Android SDK) and a Python virtual environment, then:

```sh
python3 -m pip install -r instrument/requirements.txt
PYTHONPATH=instrument python3 -m unittest discover -s instrument/tests -v
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

Passing `-PusbTestInstall=true` builds a side-by-side development variant with package `org.openbyddisplay.usbtest` and the label *OpenBYDDisplay · 开发版*.

Release builds are signed with a stable, dedicated Android key supplied through `SIGNING_KEYSTORE_PATH`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS` and `SIGNING_KEY_PASSWORD`. Private keys are never committed. See [docs/github-distribution.md](docs/github-distribution.md) for the tag, signing and certificate-fingerprint rules. Forks that publish their own builds should use their own package name, key and fingerprint.

## Usage

> Only use update packages that match your current cluster and come from a known source. Operate with the vehicle stationary and the power supply stable. Stop any casting first.

The app has a single screen:

1. **Cluster address** — defaults to `192.168.77.1`.
2. **Check cluster update capability** — reads `update.info`.
3. **GitHub releases and downloads** — opens the Releases page. Packages are downloaded manually and then picked as local files; the app does not perform automatic OTA or APK installation.
4. **Select and check package (ZIP / FUP)** — runs the local pre-check and shows file size and SHA-256. RAR archives are not accepted.
5. **Upload to cluster and verify** — streams the package; the cluster validates it.
6. **Refresh job status** — queries the current job.
7. Tick **"I have parked and verified the vehicle model and cluster hardware"**, then **Install to cluster**.
8. **Stop transfer / clear staged package** — cancels a job that has not started installing.

Status meanings: `ready` means the cluster validated the package and is waiting for install; `handed_off` only means the cluster's updater was started, not that the update completed; only `applied` with `applied=true` is a confirmed install. The phone-side pre-check cannot replace cluster-side signature verification and hardware matching, and MD5 is not proof of authenticity.

### Trying the host lab service

You can exercise the full update flow against the laboratory service on a computer. From the repository root:

```sh
export PYTHONPATH=instrument
python3 -m openbyddisplay.packages keygen lab-state/keys
mkdir -p lab-state/source
echo 'OpenBYDDisplay development data' > lab-state/source/version.txt
python3 -m openbyddisplay.packages build \
  --source lab-state/source --key lab-state/keys/development-private.pem \
  --target song-plus-dmi-2021 --hardware-id host-lab --version 0.1.0 \
  --output lab-state/song-0.1.0-lab.fup
python3 -m openbyddisplay.service \
  --state lab-state/song --public-key lab-state/keys/development-public.pem \
  --target song-plus-dmi-2021 --hardware-id host-lab --port 47785
```

For the Tang target use `tang-plus-dmi-2021` with a separate output package and state directory. **Never use lab packages on a real cluster.** Keys, packages and state directories are git-ignored and never published. Details: [docs/instrument-lab.md](docs/instrument-lab.md).

## Architecture

```
 Head unit (Android)                                   Cluster side
┌──────────────────────────────────────┐   USB NCM   ┌──────────────────────────────┐
│ MainActivity  (UI, 800 ms refresh)   │ 192.168.77.x│ update service (port 47785)  │
│   │ intents                          │  TCP, JSON  │  update.info / upload /      │
│   ▼                                  │   lines +   │  status / apply / cancel     │
│ InstrumentUpdateService (foreground) │  raw bytes  │  cast.status                 │
│   ├─ InstrumentUpdatePackage  ──────►│◄───────────►│                              │
│   │    ZIP/SV & FUP2 pre-check       │             │ today: instrument/ Python    │
│   ├─ InstrumentUpdateClient          │             │ lab service on 127.0.0.1,    │
│   │    streaming socket transport    │             │ sandbox only                 │
│   ├─ InstrumentUpdateProtocol        │             │                              │
│   │    requests, capability, stages  │             │ planned: native QNX service  │
│   ├─ ClusterWireProtocol             │             │ (not yet implemented)        │
│   │    JSON-line codec, no tokens    │             └──────────────────────────────┘
│   └─ InstrumentUpdateStore           │
│        persisted job state           │
└──────────────────────────────────────┘
```

**Upload flow:** the client sends `update.upload` with a random 32-hex `update_id`, `kind` (`firmware` / `components`) and `bytes`; waits for `ok`/`ready` for the same ID; sends exactly `bytes` raw bytes; then reads the stage reply. A `duplicate` reply never triggers a re-send. Package limits: 512 MiB for a firmware ZIP, 256 MiB payload plus header/manifest for a component package.

**FUP2 component package:** an 8-byte magic (`FUP2\r\n\x1a\n`), manifest length, reserved field, payload length, a 64-byte Ed25519 signature, the UTF-8 manifest (`format`, `version`, `package_id`, `files[]`), then the payload in manifest order. Lab packages additionally carry a signed `xhh-target.json` binding the target model and hardware ID.

Full specification: [docs/update-protocol.md](docs/update-protocol.md).

### Repository layout

```
app/                  Android head-unit app (Kotlin) and unit tests
instrument/           Host lab service, FUP2 packager, target profiles, tests
tools/                Release signing preparation and asset generation
docs/                 Protocol, lab, distribution docs and release notes
config/               Public release-certificate SHA-256 fingerprint
LICENSES/, NOTICE     Third-party licenses and attribution
```

### Roadmap

Before any flashable package for a specific cluster is published, the project needs a legitimate QNX toolchain, a native port of the service, a platform install backend, real cluster hardware identification, a recovery entry independent of the update partition, and separate verification on each target vehicle. Cluster-side casting (navigation, lyrics, fly-screen, vignette mask) builds on top of that.

## Contributing

Use feature branches and pull requests; CI runs the Python and Kotlin tests, Android Lint and uploads a development APK. Please read [AGENTS.md](AGENTS.md) and the docs before changing the update transport, install semantics or release workflows. Do **not** submit third-party APKs, firmware, decompiled code, vehicle identifiers, credentials, commercial licenses or signing private keys, and only contribute content you have the right to submit.

## Disclaimer

**Stability and safety.** This is experimental test software. Modifying a head unit or instrument cluster can make it unbootable, cause display faults, affect original vehicle functions, cause data or property loss, and may affect your warranty or regulatory compliance. Never operate the software while driving. Any support, adaptation or test conclusion applies only to the explicitly listed versions and actual evidence; "it builds", "received an ACK" or "staged" does not mean a vehicle can be safely flashed or actually displays an image.

**Trademarks and independence.** BYD, Desay, AMap (高德), NetEase Cloud Music (网易云音乐) and all other product or company names are trademarks of their respective owners. This project has no affiliation, partnership, authorization, certification or endorsement relationship with BYD, Desay, any other manufacturer or any commercial casting product. Names are used only to describe the subject of the research and grant no trademark rights. This repository distributes only its own code and dependencies that may be redistributed; it contains no third-party APKs, cluster firmware, decompiled code, vendor keys or personal vehicle data. FUP2 layout descriptions are interoperability facts.

## Acknowledgements

Special thanks to 迪加 and 远方的小熊猫智能守卫 for their help and support.

Thanks also to everyone who reports issues and shares vehicle adaptation results, and to the Kotlin, AndroidX, Gradle and `cryptography` projects.

## Support

If you find the project useful, you can sponsor it on [Afdian (爱发电)](https://afdian.com/a/wayyoung). Sponsorship is entirely voluntary and does not affect features or access to updates.

## License

[MIT License](LICENSE). The original author's and contributors' copyright notices are retained; see [NOTICE](NOTICE). The initial Android update-client core is derived from the author's MIT-licensed `xiaohuihui-cluster-cast`. Gradle wrapper, Kotlin and AndroidX components are used under the [Apache License 2.0](LICENSES/Apache-2.0.txt).
