#!/usr/bin/env python3
"""Bounded validation on fresh SDK-based AVDs; never use user account data."""
import argparse
import hashlib
import json
import os
import pathlib
import re
import shutil
import subprocess
import tempfile
import time
import xml.etree.ElementTree as ET

p = argparse.ArgumentParser()
p.add_argument('--avd', default='armbandbot_beta154_clean_api35')
p.add_argument('--classes', default='com.heyheyon.armbandbot.PostAutomationUiTest')
p.add_argument('--evidence', required=True)
p.add_argument('--live-fixture-dir')
p.add_argument('--remote-fixture')
p.add_argument('--require-all-tests', action='store_true')
p.add_argument('--instrumentation-timeout', type=int, default=250)
p.add_argument('--apk', default='app/build/outputs/apk/debug/app-debug.apk')
p.add_argument('--smoke-only', action='store_true')
p.add_argument('--upgrade-from')
p.add_argument('--width', type=int, default=432)
p.add_argument('--font', default='1.0')
a = p.parse_args()
if not 1 <= a.instrumentation_timeout <= 1200:
    raise SystemExit('Instrumentation timeout must be between 1 and 1200 seconds')
root = pathlib.Path(__file__).resolve().parents[2]
sdk = pathlib.Path.home() / 'Android/Sdk'
out = pathlib.Path(a.evidence).resolve()
out.mkdir(parents=True, exist_ok=True)
adb = str(sdk / 'platform-tools/adb')
serial = 'emulator-5554'
pkg = 'com.heyheyon.armbandbot'
apk = (root / a.apk).resolve()
env = dict(os.environ)
for location in [pathlib.Path.home()/'.config/.android/avd', pathlib.Path.home()/'.android/avd']:
    if (location / (a.avd + '.ini')).exists():
        env['ANDROID_AVD_HOME'] = str(location)
        break
else:
    raise SystemExit('Create a dedicated clean AVD with avdmanager first')

def run(args, timeout=60):
    r = subprocess.run(args, capture_output=True, text=True, timeout=timeout)
    if r.returncode:
        raise RuntimeError(f'Command failed: {args[:5]!r}: {r.stderr[:1000]}')
    return r.stdout

def shell(*args, timeout=60):
    return run([adb, '-s', serial, 'shell', *args], timeout=timeout)

def launch():
    subprocess.run([adb, '-s', serial, 'shell', 'pm', 'grant', pkg, 'android.permission.POST_NOTIFICATIONS'], capture_output=True, timeout=30)
    shell('am', 'start', '-W', '-n', pkg + '/.MainActivity')
    time.sleep(5)

if serial in run([adb, 'devices']):
    raise SystemExit('Refusing to replace an existing emulator-5554')
guest = pathlib.Path(tempfile.mkdtemp(prefix='armband-154-guest-', dir=out))
log = (out / 'emulator.log').open('w')
em = None
upgrade_checks = None
preferences = '/data/user/0/' + pkg + '/shared_prefs'
try:
    run([str(sdk/'emulator/mksdcard'), '128M', str(guest/'sdcard.img')])
    metadata = dict(line.split('=', 1) for line in (pathlib.Path(env['ANDROID_AVD_HOME'])/(a.avd+'.avd')/'config.ini').read_text().splitlines() if '=' in line)
    image = sdk / metadata['image.sysdir.1']
    extra = ['-show-kernel']
    memory = '2048' if 'android-24' in str(image) else '4096'
    if (image/'encryptionkey.img').exists():
        shutil.copyfile(image/'encryptionkey.img', guest/'encryptionkey.img')
        extra += ['-encryption-key', str(guest/'encryptionkey.img')]
    em = subprocess.Popen([str(sdk/'emulator/emulator'), '-avd', a.avd, '-data', str(guest/'userdata.img'), '-sdcard', str(guest/'sdcard.img'), '-no-cache', '-no-snapshot', '-no-window', '-gpu', 'swiftshader_indirect', '-no-audio', '-no-boot-anim', '-memory', memory, '-port', '5554'] + extra, stdout=log, stderr=subprocess.STDOUT, env=env)
    boot_deadline = time.monotonic() + 180
    while time.monotonic() < boot_deadline:
        if em.poll() is not None:
            raise RuntimeError('Emulator exited: ' + str(em.returncode))
        try:
            status = subprocess.run([adb, '-s', serial, 'shell', 'getprop', 'sys.boot_completed'], capture_output=True, text=True, timeout=10)
        except subprocess.TimeoutExpired:
            continue
        if status.stdout.strip() == '1':
            break
        time.sleep(2)
    else:
        raise RuntimeError('Guest boot deadline exceeded')
    print('Emulator boot complete', flush=True)
    api = shell('getprop', 'ro.build.version.sdk').strip()
    prior = subprocess.run([adb, '-s', serial, 'shell', 'pm', 'path', pkg], capture_output=True, text=True, timeout=30)
    if prior.stdout.strip():
        raise RuntimeError('Guest is not clean')
    for key, value in [('accelerometer_rotation','0'), ('user_rotation','0'), ('window_animation_scale','0'), ('transition_animation_scale','0'), ('animator_duration_scale','0'), ('screen_off_timeout','1800000')]:
        namespace = 'system' if key in ('accelerometer_rotation', 'user_rotation', 'screen_off_timeout') else 'global'
        shell('settings', 'put', namespace, key, value)
    shell('input', 'keyevent', 'KEYCODE_WAKEUP')
    shell('wm', 'dismiss-keyguard')
    shell('wm', 'size', f'{a.width}x960')
    shell('wm', 'density', '160')
    shell('settings', 'put', 'system', 'font_scale', a.font)
    if a.upgrade_from:
        if not a.smoke_only:
            raise ValueError('--upgrade-from requires --smoke-only')
        run([adb, '-s', serial, 'install', '-r', str((root/a.upgrade_from).resolve())])
        launch()
        shell('am', 'force-stop', pkg)
        run([adb, '-s', serial, 'root'])
        run([adb, '-s', serial, 'wait-for-device'])
        uid = shell('stat', '-c', '%u', '/data/user/0/' + pkg).strip()
        assert uid.isdecimal()
        preferences = '/data/user/0/' + pkg + '/shared_prefs'
        shell('mkdir', '-p', preferences)
        fixtures = {
            'bot_master': '<map><set name="bot_ids"><string>beta154_upgrade_fixture</string></set></map>',
            'bot_prefs_beta154_upgrade_fixture': '<map><string name="bot_name">업데이트 유지 검증</string><string name="target_urls">https://gall.dcinside.com/mgallery/board/lists/?id=laboratory1</string><string name="user_blacklist">fixture-user-to-preserve</string><boolean name="is_running" value="false"/><boolean name="is_user_filter_mode" value="false"/></map>'
        }
        for name, data in fixtures.items():
            host = guest/(name + '.xml')
            host.write_text(data)
            temp = '/data/local/tmp/' + name + '.xml'
            target = preferences + '/' + name + '.xml'
            run([adb, '-s', serial, 'push', str(host), temp])
            shell('cp', temp, target)
            shell('chown', f'{uid}:{uid}', target)
            shell('chmod', '600', target)
            shell('rm', temp)
        launch()
        shell('am', 'force-stop', pkg)
        run([adb, '-s', serial, 'pull', preferences, str(out/'prefs-before')])
    run([adb, '-s', serial, 'install', '-r', str(apk)])
    print('Target APK installed', flush=True)
    if a.smoke_only:
        launch()
        assert shell('pidof', pkg).strip(), 'App process missing'
        shell('uiautomator', 'dump', '/sdcard/automation-smoke.xml')
        xml = shell('cat', '/sdcard/automation-smoke.xml')
        (out/'ui.xml').write_text(xml)
        assert f'package="{pkg}"' in xml, 'Expected UI missing'
        screen = subprocess.run([adb, '-s', serial, 'exec-out', 'screencap', '-p'], capture_output=True, timeout=30, check=True)
        (out/'smoke.png').write_bytes(screen.stdout)
        crash = shell('logcat', '-d', '-b', 'crash')
        (out/'crash.txt').write_text(crash)
        assert 'FATAL EXCEPTION' not in crash, 'App crashed'
        if a.upgrade_from:
            shell('am', 'force-stop', pkg)
            run([adb, '-s', serial, 'pull', preferences, str(out/'prefs-after')])
            def values(folder, name):
                return {node.get('name'): (node.text if node.tag == 'string' else node.get('value')) for node in ET.parse(out/folder/(name+'.xml')).getroot()}
            before = values('prefs-before', 'bot_prefs_beta154_upgrade_fixture')
            after = values('prefs-after', 'bot_prefs_beta154_upgrade_fixture')
            keys = ['bot_name', 'target_urls', 'user_blacklist', 'is_running', 'is_user_filter_mode']
            assert all(before[k] == after[k] for k in keys), 'Previous settings changed'
            master = ET.parse(out/'prefs-after'/'bot_master.xml').getroot()
            registered = master.find("string[@name='bot_ids_list']")
            assert registered is not None and 'beta154_upgrade_fixture' in (registered.text or '').split(','), 'Bot registration lost'
            assert '업데이트 유지 검증' in xml, 'Upgraded fixture is not rendered on bot list'
            upgrade_checks = dict(previous_settings_retained=keys, bot_registration_retained=True, source=str((root/a.upgrade_from).resolve()), row_retention='covered separately by Room migration/DAO instrumentation, not asserted by this preference fixture')
        summary = dict(api=api, mode='native-release-smoke', passed=True, width=a.width, font=a.font, artifact=str(apk), sha256=hashlib.sha256(apk.read_bytes()).hexdigest(), upgrade=upgrade_checks)
        (out/'summary.json').write_text(json.dumps(summary, indent=2, ensure_ascii=False))
        print(json.dumps(summary, ensure_ascii=False))
    else:
        if apk.parent.name == 'release':
            raise ValueError('Signed release smoke and debug instrumentation are separate variants')
        run([adb, '-s', serial, 'install', '-r', str(root/'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')])
        print('Test APK installed', flush=True)
        if a.live_fixture_dir:
            private = pathlib.Path(a.live_fixture_dir)
            shell('run-as', pkg, 'mkdir', '-p', 'files')
            for host, name in [('automation154-browser-cookie.txt','automation_live_cookie.txt'), ('automation154-fixture.json','automation_live_fixture.json')]:
                command = [adb, '-s', serial, 'shell', 'run-as', pkg, 'sh', '-c', f"'cat > files/{name}; chmod 600 files/{name}'"]
                staged = subprocess.run(command, input=(private/host).read_bytes(), capture_output=True, timeout=30)
                if staged.returncode:
                    raise RuntimeError('Secure fixture staging failed')
        command = [adb, '-s', serial, 'shell', 'am', 'instrument', '-w', '-r', '-e', 'class', a.classes]
        if a.live_fixture_dir:
            command += ['-e','automation_live','authorized_fixture']
        if a.remote_fixture:
            command += ['-e', 'remote_fixture', a.remote_fixture]
        command += [pkg+'.test/androidx.test.runner.AndroidJUnitRunner']
        with (out/'instrumentation.txt').open('w') as output:
            try:
                executed = subprocess.run(command, stdout=output, stderr=subprocess.STDOUT, timeout=a.instrumentation_timeout)
            except subprocess.TimeoutExpired:
                (out/'logcat.txt').write_text(shell('logcat', '-d'))
                raise
        result = (out/'instrumentation.txt').read_text()
        (out/'logcat.txt').write_text(shell('logcat', '-d'))
        subprocess.run([adb, '-s', serial, 'pull', '/sdcard/Android/data/'+pkg+'/files', str(out/'screens')], capture_output=True, timeout=30)
        crash = shell('logcat', '-d', '-b', 'crash')
        (out/'crash.txt').write_text(crash)
        codes = [int(code) for code in re.findall(r'^INSTRUMENTATION_STATUS_CODE:\s*(-?\d+)', result, re.M)]
        declared_values = set(int(value) for value in re.findall(r'^INSTRUMENTATION_STATUS: numtests=(\d+)', result, re.M))
        declared = next(iter(declared_values)) if len(declared_values) == 1 else None
        completed, skipped, failed = codes.count(0), sum(codes.count(code) for code in (-3, -4)), sum(codes.count(code) for code in (-1, -2))
        counted = declared is not None and completed + skipped + failed == declared
        ok = executed.returncode == 0 and counted and failed == 0 and 'OK (' in result and 'FAILURES!!!' not in result and 'INSTRUMENTATION_FAILED' not in result and 'FATAL EXCEPTION' not in crash
        if a.require_all_tests:
            ok = ok and skipped == 0 and completed == declared
        summary = dict(api=api, classes=a.classes, passed=ok, declared=declared, completed=completed, skipped=skipped, failed=failed, require_all_tests=a.require_all_tests, width=a.width, font=a.font, artifact=str(apk), sha256=hashlib.sha256(apk.read_bytes()).hexdigest())
        (out/'summary.json').write_text(json.dumps(summary, indent=2))
        print(result[-2500:])
        print(json.dumps(summary))
        if not ok:
            raise RuntimeError('Instrumentation acceptance failed')
finally:
    if em is not None and em.poll() is None:
        subprocess.run([adb, '-s', serial, 'emu', 'kill'], capture_output=True, timeout=15)
        try:
            em.wait(timeout=20)
        except subprocess.TimeoutExpired:
            em.terminate()
            em.wait(timeout=10)
    log.close()
    shutil.rmtree(guest, ignore_errors=True)
