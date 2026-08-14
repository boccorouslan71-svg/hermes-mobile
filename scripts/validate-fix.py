from pathlib import Path

root = Path(__file__).resolve().parents[1]
workflow = (root / '.github/workflows/android.yml').read_text()
activity = (root / 'android/app/src/main/java/com/hermesmobile/MainActivity.kt').read_text()
server = (root / 'android/app/src/main/java/com/hermesmobile/runtime/HermesLocalServer.kt').read_text()
installer = (root / 'android/app/src/main/java/com/hermesmobile/runtime/HermesRuntimeInstaller.kt').read_text()
supervisor = (root / 'android/app/src/main/java/com/hermesmobile/runtime/HermesProcessSupervisor.kt').read_text()
html = (root / 'android/app/src/main/assets/web/index.html').read_text()
checks = {
    'bundled WebView asset': 'file:///android_asset/web/index.html' in activity,
    'polling localhost bridge': "BRIDGE+'/api/status'" in html and 'setTimeout(pollBridge,2000)' in html,
    'server binds loopback': 'ServerSocket(PORT, 16' in server,
    'server starts before install': 'localServer.start()' in supervisor and 'installer.install()' in supervisor,
    'bootstrap asset support': 'bootstrap-aarch64.zip' in installer,
    'Hermes Termux extra': ".['termux']" in installer or "'.[termux]'" in installer,
    'gateway launch': 'runHermes(listOf("gateway"))' in supervisor,
    'CI downloads bootstrap': 'api.github.com/repos/termux/termux-packages/releases/latest' in workflow and 'bootstrap-aarch64.zip' in workflow,
    'CI builds APK': 'assembleDebug' in workflow,
    'SYMLINKS manifest support': 'SYMLINKS.txt' in installer and 'Os.symlink' in installer and '←' in installer,
    'encoded symlink support': 'SYMLINK→' in installer,
    'persistent config endpoint': 'GET' in server and '/api/config' in server and 'hermes-config.json' in server,
    'settings UI': 'settingsButton' in html and 'apiKey' in html and 'saveSettings' in html,
    'full install log endpoint': 'installLog' in server and 'installLogFile' in installer,
}
failed = [name for name, passed in checks.items() if not passed]
for name, passed in checks.items():
    print(f"{'PASS' if passed else 'FAIL'}: {name}")
if failed:
    raise SystemExit('Validation failed: ' + ', '.join(failed))
