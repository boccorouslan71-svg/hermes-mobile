# External facts used for the Hermes Mobile fix

## Hermes Agent Android / Termux documentation
Source: https://hermes-agent.nousresearch.com/docs/getting-started/termux

The official Hermes documentation describes Android/Termux as a Tier 2 platform. The tested bundle is installed with `python -m pip install -e '.[termux]' -c constraints-termux.txt`; the minimal core path is `python -m pip install -e '.' -c constraints-termux.txt`. The manual prerequisites are `git python clang rust make pkg-config libffi openssl nodejs ripgrep ffmpeg`. The documented sequence creates a Python venv, sets `ANDROID_API_LEVEL` to the Android SDK level, upgrades pip/setuptools/wheel, installs the Termux extra, and links `venv/bin/hermes` into `$PREFIX/bin/hermes`. The browser/Playwright bootstrap, Docker isolation, and voice extra are not part of the tested Android path.

## Hermes installer
Source: https://github.com/NousResearch/hermes-agent/blob/main/scripts/install.sh

The upstream installer is Termux-aware and uses Python's stdlib venv plus pip on Termux, rather than uv. It supports the tested Termux extras and skips unsupported browser bootstrapping.

## Termux bootstrap releases
Source: https://github.com/termux/termux-packages/releases

The latest release observed in the current session is `bootstrap-2026.08.09-r1+apt.android-7`. The releases page contains the ARM64 asset `bootstrap-aarch64.zip` under each bootstrap release. CI should resolve the latest release through the GitHub API, download the asset, verify a checksum when available, and place it at `android/app/src/main/assets/bootstrap-aarch64.zip` before Gradle packaging.
