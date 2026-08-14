import json
import pathlib
import urllib.request

root = pathlib.Path(__file__).resolve().parents[1]
out = root / 'android/app/src/main/assets/bootstrap-aarch64.zip'
api = 'https://api.github.com/repos/termux/termux-packages/releases/latest'
request = urllib.request.Request(api, headers={'Accept': 'application/vnd.github+json', 'User-Agent': 'hermes-mobile-local-test'})
with urllib.request.urlopen(request, timeout=30) as response:
    release = json.load(response)
assets = [asset for asset in release.get('assets', []) if asset.get('name') == 'bootstrap-aarch64.zip']
if not assets:
    raise SystemExit(f"bootstrap-aarch64.zip not found in {release.get('tag_name')}")
asset = assets[0]
print(f"Downloading {release.get('tag_name')} from {asset['browser_download_url']}")
out.parent.mkdir(parents=True, exist_ok=True)
request = urllib.request.Request(asset['browser_download_url'], headers={'User-Agent': 'hermes-mobile-local-test'})
with urllib.request.urlopen(request, timeout=120) as response, out.open('wb') as destination:
    while True:
        chunk = response.read(1024 * 1024)
        if not chunk:
            break
        destination.write(chunk)
print(out)
