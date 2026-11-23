# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Overview

TodoDrop is two programs that talk over a LAN protocol:

- `android/` — Kotlin + Jetpack Compose + Room app ("待办投递"). Todos are grouped into projects (up to 3 levels of sub-projects). It sends selected unfinished todos to a PC by scanning a QR code.
- `cli/` — Rust crate `tododrop` (the installed binary is `tdd`), the PC receiver. It starts an HTTPS server, prints a QR code in the terminal, takes the raw project tree and writes one Markdown file per top-level project.
- `docs/PROTOCOL.md` — the wire protocol. **Both sides implement it, so it is the source of truth.** Any change to the payload, endpoints or Markdown output must update this doc and both implementations together.

The code, comments, user-facing strings, docs and commit messages are all written in Chinese. Keep that convention.

## Commands

CLI (needs Rust ≥ 1.88; crypto backend is `ring`, so NASM is not needed on Windows):

```bash
cd cli
cargo build --release
cargo test                      # unit tests + tests/e2e.rs (real HTTPS + fingerprint pinning)
cargo test <name>               # single test by name filter
cargo test --test e2e           # only the end-to-end tests
```

Android (use `gradlew.bat` from cmd/PowerShell):

```bash
cd android
./gradlew :app:testDebugUnitTest                                  # JVM unit tests (src/test)
./gradlew :app:testDebugUnitTest --tests '*.QrPayloadTest'        # single test class
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease  # R8 on; debug-signed unless TODODROP_KEYSTORE* env vars are set
```

`android/gradle/wrapper/gradle-wrapper.properties` points at an Aliyun mirror. CI rewrites it to services.gradle.org with `sed`, so don't "fix" it in the repo.

## Architecture

### Transfer flow (spans both sides)
1. On each start the CLI makes a new self-signed ECDSA P-256 cert (`tls.rs`, never written to disk) and a 16-byte token (`lib.rs`). It picks up to 4 LAN IPv4 candidates (`net.rs` filters out virtual NICs and TUN ranges and ranks VPN/overlay NICs last). Then it renders `tododrop://v1?h=..&p=..&t=..&f=..&n=..` as a QR code (`qr.rs`).
2. The app parses the QR code (`transfer/QrPayload.kt`). It probes all hosts at once with `GET /api/v1/info` and uses the first one that answers (`transfer/TransferClient.kt`). TLS is checked by pinning the cert's SHA-256 against `f` (`PinnedHttpClient.kt`), not through a CA chain.
3. The app builds the raw project tree (`export/ExportTree.kt`) and sends `POST /api/v2/todos` with a Bearer token. The CLI (`server.rs`) renders Markdown (`markdown.rs`) and writes the files (`storage.rs`): create-new only, `_2`/`_3` suffixes, all files of one send or none. Notes ("提醒") are never written to files. They are printed in the terminal and returned to the app.
4. When the send succeeds, the app marks the sent todos as completed and records an export-history entry.

Protocol version is `PROTOCOL_VERSION = 2`, defined in both `cli/src/lib.rs` and `TransferClient.kt`. The QR scheme stays `tododrop://v1` and `/api/v1/info` stays at the same path across versions. A version mismatch shows up in the app as "版本不兼容", so bump both sides together.

### CLI
`lib.rs` exposes every module so `tests/e2e.rs` can drive the real server. `main.rs` only parses arguments (serve vs. `config` subcommand) and handles terminal output. The output directory is chosen in this order: `-o` > `default_dir` in the config file (`TODODROP_CONFIG` overrides the path) > the current directory.

### Android
- `AppContainer` is a hand-written DI container passed down through the `LocalAppContainer` CompositionLocal. There is no Hilt/Koin.
- `data/`: the Room DB (`TodoDropDatabase`, version 2, `exportSchema = false`, with hand-written `MIGRATION_1_2`) holds projects (self-referencing `parentId`), todos (a regular todo or a note) and export history. `ProjectTree.kt` and `SortOrders.kt` hold pure logic for the tree and drag-reorder, and that logic is unit-tested. A schema change needs a new hand-written migration and a version bump.
- `backup/`: JSON backup format v2. `BackupCodec` must keep importing v1 backups.
- `ui/`: Compose screens, each with a ViewModel where needed, wired together in `ui/navigation/TodoDropNavHost.kt`.
- Only `arm64-v8a` is built. minSdk 28, targetSdk 36.

## Releasing

CI (`.github/workflows/ci.yml`) runs `cargo test --locked` on 3 OSes, plus the Android unit tests and a debug build. `release.yml` runs on a `v*` tag and **fails unless the tag matches both `cli/Cargo.toml` `version` and `android/app/build.gradle.kts` `versionName`**. To release:
1. Bump both versions, plus `versionCode`.
2. Run `cargo build` in `cli/` to refresh `Cargo.lock`, since CI uses `--locked`.
3. Tag and push.

Tags that contain `-` are published as pre-releases.
