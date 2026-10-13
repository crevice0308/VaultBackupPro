"""Disposable real-server regression harness for the pinned manual-stop release.

No game/server downloads, production files, or existing fixture mutation. The caller
must supply the release artifact's independently provided SHA-256. All commands are
issued on the local fixture server console. The `stop` command explicitly simulates
an administrator choosing to shut down; it is never used to assert plugin auto-stop.
"""
from __future__ import annotations
import base64
import datetime as dt
import hashlib
import io
import json
import os
from pathlib import Path
import queue
import re
import shutil
import socket
import subprocess
import threading
import time
import urllib.parse
import zipfile

def required_path(name):
    value = os.environ.get(name)
    if not value:
        raise SystemExit(f'Set {name} to an explicit path on your machine; see the runtime-tests README.')
    return Path(value).expanduser().resolve()


BASE = required_path('VAULTBACKUP_TEST_BASE')
TOOLS = required_path('VAULTBACKUP_TEST_TOOLS')
PROJECT = Path(os.environ.get('VAULTBACKUP_TEST_PROJECT', str(Path(__file__).resolve().parents[2]))).expanduser().resolve()
RUN_TAG = os.environ.get('VAULTBACKUP_TEST_RUN', 'manualstop-revision')
assert re.fullmatch(r'manualstop-revision(?:-[A-Za-z0-9_-]+)?', RUN_TAG), 'Invalid disposable test run tag'
ROOT = BASE / RUN_TAG
LOGS = BASE / (RUN_TAG + '-logs')
JAVA = os.environ.get('VAULTBACKUP_TEST_JAVA', str(TOOLS / 'jdk/bin/java'))
SOURCE = Path(os.environ.get('VAULTBACKUP_TEST_PLUGIN', str(PROJECT / 'build/libs/VaultBackupPro-1.0.0.jar')))
VARIANTS = ['paper-26.2', 'paper-26.3', 'purpur-26.2', 'purpur-26.3', 'spigot-26.2', 'spigot-26.3']
ANSI = re.compile(r'\x1b\[[0-?]*[ -/]*[@-~]')
SERVER_HASHES = {
    'paper-26.2': 'bf1dcf627364f8a631ab15250d240eb5a8e89faaac523b391e1c61367fce9ff1',
    'paper-26.3': '5dd0c8fb20a96eb7c98f3c4b1c890e99ef6db56fd689a2cf06975a3471fda757',
    'purpur-26.2': '41f7c0535c1cec47ede48d25c8d9bd7f053d4dc44f87293e5a007314dbcfc32c',
    'purpur-26.3': '7e36f3b09d394307ae9a6a233f38a0564e8429c4c1141f468ac743220fb01a41',
    'spigot-26.2': '675c766c5e4d13184a10316fbc89224de975b0c83d404a01462618a0742d406a',
    'spigot-26.3': '59f4a101e03012e5182e43c77f06679cdcb15d779c7c4ab6899200c567d79537',
}




def foreground_before(text, token):
    """Interpret SGR resets/colors up to the actual text, not a nearby banner."""
    prefix = text[:text.index(token)]
    foreground = None
    for match in re.finditer(r'\x1b\[([0-9;]*)m', prefix):
        values = [int(value) if value else 0 for value in match.group(1).split(';')]
        for value in values:
            if value in (0, 39):
                foreground = None
            elif 30 <= value <= 37 or 90 <= value <= 97:
                foreground = value
    return foreground


def stamp():
    return dt.datetime.now(dt.timezone.utc).isoformat()


def sha256(path):
    h = hashlib.sha256()
    with Path(path).open('rb') as stream:
        while chunk := stream.read(1024 * 1024):
            h.update(chunk)
    return h.hexdigest()


def props(path):
    return dict(line.split('=', 1) for line in Path(path).read_text().splitlines()
                if line and not line.startswith('#') and '=' in line)


def write_json(path, value):
    path = Path(path)
    tmp = path.with_suffix(path.suffix + '.tmp')
    tmp.write_text(json.dumps(value, indent=2, ensure_ascii=False) + '\n')
    tmp.replace(path)


def pin_artifact(expected_hash, source=SOURCE):
    assert os.environ.get('VAULTBACKUP_TEST_EULA_ACCEPTED') == 'true', 'Read and accept the Minecraft EULA before setting VAULTBACKUP_TEST_EULA_ACCEPTED=true'
    ROOT.mkdir(exist_ok=True)
    LOGS.mkdir(exist_ok=True)
    actual = sha256(source)
    assert re.fullmatch(r'[0-9a-f]{64}', expected_hash), 'Full lowercase SHA-256 required'
    assert actual == expected_hash, ('Artifact changed; refusing startup', actual, expected_hash)
    pin = LOGS / 'tested-VaultBackupPro-1.0.0.jar'
    if pin.exists():
        assert sha256(pin) == actual, 'Preserve old evidence; use a new evidence directory for another artifact'
    else:
        shutil.copy2(source, pin)
    assert sha256(pin) == expected_hash
    with zipfile.ZipFile(pin) as jar:
        nested = jar.read('vaultbackup-restore.jar')
        classes = sorted(n for n in jar.namelist() if n.endswith('.class'))
        descriptor = jar.read('plugin.yml').decode()
        config = jar.read('config.yml').decode()
        japanese_messages = jar.read('messages/ja_JP.properties').decode()
        english_messages = jar.read('messages/en_US.properties').decode()
        assert 'language: ja_JP' in config
        assert 'startup.success=復元成功を検証しました' in japanese_messages
        assert 'startup.success=RESTORE VERIFIED SUCCESS' in english_messages
    with zipfile.ZipFile(io.BytesIO(nested)) as helper:
        helper_classes = sorted(n for n in helper.namelist() if n.endswith('.class'))
    inspection = {
        'inspected_at_utc': stamp(), 'plugin_sha256': actual,
        'plugin_bytes': pin.stat().st_size, 'plugin_classes': classes,
        'plugin_class_count': len(classes), 'helper_sha256': hashlib.sha256(nested).hexdigest(),
        'helper_bytes': len(nested), 'helper_classes': helper_classes,
        'helper_class_count': len(helper_classes),
        'all_runtime_classes_under_project_namespace': all(n.startswith('com/vaultbackup/') for n in classes + helper_classes),
        'plugin_descriptor': descriptor, 'default_language_ja_JP': True,
        'ja_JP_and_en_US_message_bundles_present': True,
    }
    assert inspection['all_runtime_classes_under_project_namespace']
    assert 'com.vaultbackup.plugin.VaultBackupPlugin' in descriptor
    assert "api-version: '26.2'" in descriptor
    write_json(LOGS / 'artifact-inspection.json', inspection)
    return pin, inspection, config


def prepare_fixture(variant, pin, language_key='language', language='ja_JP'):
    directory = ROOT / variant
    assert not directory.exists(), f'Fixture already exists: {directory}; retain previous evidence and inspect before retry'
    if variant.startswith('spigot-'):
        prepare_spigot_fixture(variant, directory)
    else:
        shutil.copytree(BASE / variant, directory, ignore=shutil.ignore_patterns('logs', '*.log', '.paper-remapped'))
    assert sha256(directory / 'server.jar') == SERVER_HASHES[variant], 'Server artifact changed from verified build'
    shutil.copy2(pin, directory / 'plugins/VaultBackupPro-1.0.0.jar')
    config_path = directory / 'plugins/VaultBackupPro/config.yml'
    config = config_path.read_text()
    # Paths were already sibling-relative in the baseline. New ROOT isolates both
    # archive and state paths without overwriting any baseline backup or journal.
    config = re.sub(r'(?m)^  interval-minutes:.*$', '  interval-minutes: 1440', config)
    config = re.sub(r'(?m)^  max-count:.*$', '  max-count: 20', config)
    config = re.sub(r'(?m)^' + re.escape(language_key) + r':.*\n?', '', config)
    if variant != 'paper-26.2':
        config = f'{language_key}: {language}\n' + config
    # Paper 26.2 deliberately omits language to exercise the ja_JP default.
    config_path.write_text(config)
    marker = f'VaultBackup manual-stop revision baseline: {variant}\n'.encode()
    (directory / 'vaultbackup-fixture.txt').write_bytes(marker)
    assert (directory / 'eula.txt').read_text().strip().endswith('true')
    assert 'server-ip=127.0.0.1' in (directory / 'server.properties').read_text()
    return marker



def prepare_spigot_fixture(variant, directory):
    jar = TOOLS / variant / (variant + '.jar')
    assert jar.is_file(), f'Official BuildTools server is not ready: {jar}'
    # This is a new isolated EULA-approved fixture, never a copy of user data.
    directory.mkdir()
    shutil.copy2(jar, directory / 'server.jar')
    (directory / 'plugins/VaultBackupPro').mkdir(parents=True)
    config = (BASE / 'paper-26.2/plugins/VaultBackupPro/config.yml').read_text()
    config = config.replace('../paper-26.2-', '../' + variant + '-')
    (directory / 'plugins/VaultBackupPro/config.yml').write_text(config)
    port = 25585 if variant == 'spigot-26.2' else 25586
    properties = (BASE / 'paper-26.2/server.properties').read_text()
    properties = re.sub(r'(?m)^server-port=.*$', 'server-port=' + str(port), properties)
    (directory / 'server.properties').write_text(properties)
    (directory / 'eula.txt').write_text('# Minecraft EULA explicitly accepted by the operator for isolated server tests.\neula=true\n')


def set_language(variant, language_key, language):
    path = ROOT / variant / 'plugins/VaultBackupPro/config.yml'
    config = re.sub(r'(?m)^' + re.escape(language_key) + r':.*\n?', '', path.read_text())
    path.write_text(f'{language_key}: {language}\n' + config)


class Server:
    def __init__(self, variant, phase):
        self.variant = variant
        self.directory = ROOT / variant
        self.lines = []
        self.q = queue.Queue()
        self.closed = False
        self.log_path = LOGS / f'{variant}-{phase}.log'
        assert not self.log_path.exists(), 'Refuse to replace a previous console log'
        self.log = self.log_path.open('w')
        proxy = urllib.parse.urlparse(os.environ.get('HTTPS_PROXY', ''))
        args = ['-Xms256M', '-Xmx768M', '-Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts']
        if variant.startswith('spigot-'):
            # Official CraftBukkit ColouredConsoleSender supports this option for
            # ANSI passthrough when stdout is captured rather than a real terminal.
            args.append('-Djansi.passthrough=true')
        if proxy.hostname:
            args += ['-Dhttps.proxyHost=' + proxy.hostname, '-Dhttps.proxyPort=' + str(proxy.port),
                     '-Dhttp.proxyHost=' + proxy.hostname, '-Dhttp.proxyPort=' + str(proxy.port)]
        self.p = subprocess.Popen([JAVA] + args + ['-jar', 'server.jar', '--nogui'], cwd=self.directory,
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
            text=True, encoding='utf-8', errors='replace', bufsize=1)
        def reader():
            for line in self.p.stdout:
                self.q.put(line)
            self.q.put(None)
        self.reader = threading.Thread(target=reader, daemon=True)
        self.reader.start()

    def read(self, timeout=1):
        try:
            line = self.q.get(timeout=timeout)
        except queue.Empty:
            return None
        if line is not None:
            self.lines.append(line)
            self.log.write(line)
            self.log.flush()
            if any(key in line for key in ['VaultBackup', 'VAULTBACKUP', 'ERROR']):
                print(self.variant, ANSI.sub('', line.strip()), flush=True)
        return line

    def drain(self):
        while not self.q.empty():
            self.read(0)

    def expect(self, pattern, timeout=120, start=0):
        until = time.monotonic() + timeout
        cursor = start
        while time.monotonic() < until:
            for line in self.lines[cursor:]:
                if re.search(pattern, ANSI.sub('', line)):
                    return line
            cursor = len(self.lines)
            self.read()
            if self.p.poll() is not None and self.q.empty():
                raise RuntimeError(f'Server exited while waiting for {pattern!r}')
        raise TimeoutError(f'Waiting for {pattern!r}; see {self.log_path}')

    def cmd(self, command):
        assert self.p.poll() is None, 'Cannot command an exited server'
        self.drain()
        start = len(self.lines)
        self.log.write(f'TEST COMMAND [{stamp()}]: {command}\n')
        self.log.flush()
        self.p.stdin.write(command + '\n')
        self.p.stdin.flush()
        return start

    def stay_alive(self, seconds):
        started = time.monotonic()
        until = started + seconds
        while time.monotonic() < until:
            self.read(max(0, min(.25, until - time.monotonic())))
            assert self.p.poll() is None, 'Server auto-stopped before administrator stop'
        return time.monotonic() - started

    def finish(self):
        # This is the authorized fixture administrator's explicit console stop.
        assert self.p.poll() is None, 'Server stopped before explicit manual stop'
        self.cmd('stop')
        until = time.monotonic() + 120
        while self.p.poll() is None and time.monotonic() < until:
            self.read()
        if self.p.poll() is None:
            raise TimeoutError('Server did not stop normally; preserving the running process and fixture')
        self.reader.join(timeout=3)
        self.drain()
        self.log.close()
        self.closed = True
        assert self.p.returncode == 0, self.p.returncode
        return self.p.returncode


def zipcheck(path, marker, expected_plugin_hash):
    count = total = 0
    with zipfile.ZipFile(path) as archive:
        assert archive.testzip() is None
        assert archive.read('vaultbackup-fixture.txt') == marker
        assert hashlib.sha256(archive.read('plugins/VaultBackupPro-1.0.0.jar')).hexdigest() == expected_plugin_hash
        names = archive.namelist()
        for required in ['server.jar', 'world/level.dat', '.vaultbackup-manifest']:
            assert required in names, required
        world_payloads = [n for n in names if n.startswith('world/dimensions/minecraft/overworld/') or n.startswith('world/region/')]
        assert world_payloads, 'Archive lacks world chunk data'
        library_payloads = [n for n in names if n.startswith(('libraries/', 'bundler/libraries/'))]
        if any((path.parent.parent / path.parent.name.removesuffix('-backups') / candidate).is_dir() for candidate in ('libraries', 'bundler/libraries')):
            assert library_payloads, 'Runtime libraries missing from archive'
        for line in archive.read('.vaultbackup-manifest').decode().splitlines()[1:]:
            kind, size, expected, encoded = line.split('\t')
            name = base64.urlsafe_b64decode(encoded + '=' * (-len(encoded) % 4)).decode()
            count += 1
            if kind == 'F':
                h = hashlib.sha256()
                actual = 0
                with archive.open(name) as stream:
                    while chunk := stream.read(1024 * 1024):
                        h.update(chunk)
                        actual += len(chunk)
                assert actual == int(size), (name, actual, size)
                assert h.hexdigest() == expected, name
                total += actual
    return {'manifest_entries_verified': count, 'uncompressed_bytes_verified': total, 'sha256': sha256(path),
            'world_payload_entries': len(world_payloads), 'library_entries': len(library_payloads)}


def wait_status(job, statuses, timeout=240):
    until = time.monotonic() + timeout
    last = None
    while time.monotonic() < until:
        path = job / 'status.properties'
        if path.exists():
            current = props(path)['status']
            if current != last:
                print('HELPER', job.name, current, flush=True)
                last = current
            if current in statuses:
                return current
            if current in {'RECOVERY_REQUIRED', 'FAILED_BEFORE_SWAP', 'FAILED_ROLLED_BACK', 'EXPIRED'}:
                raise RuntimeError(f'Helper terminal failure: {current}: {props(path).get("message", "")}')
        time.sleep(.25)
    raise TimeoutError(f'Helper did not reach {statuses}; last {last}')


def active_job(variant):
    state = ROOT / (variant + '-state')
    active = props(state / 'active-restore.properties')
    return state / 'restore-jobs' / active['jobId'], active


def ports_closed():
    results = {}
    for port in range(25581, 25588):
        with socket.socket() as sock:
            sock.settimeout(.3)
            results[str(port)] = sock.connect_ex(('127.0.0.1', port)) != 0
    assert all(results.values()), results
    return results
