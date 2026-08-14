import json
import pathlib
import urllib.request

root = pathlib.Path(__file__).resolve().parents[1]
workflow = (root / '.github/workflows/android.yml').read_text()
required = [
    'api.github.com/repos/termux/termux-packages/releases/latest',
    "a['name'] == 'bootstrap-aarch64.zip'",
    'android/app/src/main/assets/bootstrap-aarch64.zip',
    'assembleDebug',
    'actions/upload-artifact@v4',
    'softprops/action-gh-release@v2',
]
for item in required:
    if item not in workflow:
        raise SystemExit(f'Missing workflow contract: {item}')
request = urllib.request.Request(
    'https://api.github.com/repos/termux/termux-packages/releases/latest',
    headers={'Accept': 'application/vnd.github+json', 'User-Agent': 'hermes-mobile-ci-check'},
)
with urllib.request.urlopen(request, timeout=30) as response:
    release = json.load(response)
assets = {asset.get('name'): asset.get('browser_download_url') for asset in release.get('assets', [])}
url = assets.get('bootstrap-aarch64.zip')
if not url or not url.startswith('https://github.com/termux/termux-packages/releases/download/'):
    raise SystemExit('Latest Termux release has no valid ARM64 bootstrap URL')
print('PASS: workflow contract')
print(f"PASS: latest release {release.get('tag_name')} -> {url}")
