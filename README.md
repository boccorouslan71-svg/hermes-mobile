# Hermes Mobile

Hermes Mobile is an Android container for running a local Hermes Agent runtime behind a WebView interface. It follows the local-first architecture described by AnyClaw: an Android shell extracts a private Linux-like prefix, a foreground service keeps the runtime alive, a localhost HTTP bridge separates the UI from the agent process, and the WebView provides the user interface.

> This repository provides the complete Android shell and integration points. The Hermes executable and a pinned ARM64 bootstrap archive are build inputs rather than opaque network downloads at application runtime.

## Architecture

| Component | Responsibility |
|---|---|
| `MainActivity` | Starts the foreground service and loads the localhost WebView. |
| `HermesForegroundService` | Holds Android foreground priority and supervises runtime lifetime. |
| `HermesRuntimeInstaller` | Extracts `bootstrap-aarch64.zip` into app-private storage with path traversal protection. |
| `HermesProcessSupervisor` | Creates the Hermes environment and provides the process launch boundary. |
| `HermesLocalServer` | Serves the UI and exposes `/api/status`, `/api/message`, and `/api/events` on `127.0.0.1:18923`. |
| `assets/web/index.html` | Standalone chat interface for the WebView. |
| `.github/workflows/android.yml` | Reproducible CI build and tag-triggered GitHub release. |

The bridge is deliberately bound to loopback. It does not expose a remote API, and the WebView cannot access arbitrary local files. A production distribution should add a random local bearer token, explicit approval handling, a workspace allow-list, and a signed/pinned bootstrap manifest before enabling powerful tools.

## Build locally

Requirements are Java 17, Android SDK platform 35, Android build-tools 35.0.0, and an ARM64 Hermes payload if the full runtime is desired.

```bash
cd android
./gradlew assembleDebug
```

The APK is written to `android/app/build/outputs/apk/debug/app-debug.apk`. The current shell also builds without the optional bootstrap archive; in that mode the application starts its localhost bridge and reports that the Hermes executable is not installed.

## Preparing the runtime payload

Runtime fetching is kept outside the APK launch path. Set a versioned URL and, preferably, a SHA-256 checksum before running:

```bash
export BOOTSTRAP_URL='https://example.invalid/termux-bootstrap-aarch64.zip'
export BOOTSTRAP_SHA256='sha256-from-a-release-manifest'
export HERMES_BINARY="$PWD/path/to/hermes-arm64"
./scripts/build-bootstrap.sh
cd android && ./gradlew assembleDebug
```

The bootstrap archive must be compatible with the target Android ABI and must include the shell and libraries needed by Hermes. The application expects the optional executable at `bin/hermes` inside the extracted prefix. The bootstrap should be generated from a pinned Hermes release and checked into a controlled artifact store rather than rebuilt from `latest`.

## GitHub Actions and releases

Pushes to `main` build the debug APK and upload it as a workflow artifact. Pushing a tag such as `v0.1.0` also creates a GitHub release and attaches the APK. Repository variables `HERMES_BOOTSTRAP_URL` and `HERMES_BOOTSTRAP_SHA256` may be configured to make the asset preparation deterministic. The workflow intentionally refuses to claim a production Hermes runtime when those variables are absent.

## Security notes

The local server is HTTP over loopback because Android WebView needs a local transport and the server never binds beyond `127.0.0.1`. Do not change the bind address without adding authentication and origin checks. Do not grant `danger-full-access` or disable approvals by default. The target SDK is 28 to preserve compatibility with executing binaries from app-private storage, matching the Termux-style approach, but this is a compatibility trade-off that should be revalidated on every Android release.

## License

The Android shell and integration code in this repository is released under the MIT License. Hermes Agent remains under its upstream license and must be included or distributed according to the [NousResearch repository](https://github.com/NousResearch/hermes-agent).
