#!/usr/bin/env python3
"""Real-server acceptance run for VaultBackup Pro 1.3.x, driven from the console and RCON.

Starts real Paper and Spigot servers in new, disposable, loopback-only fixtures and checks what
only a real server shows: loading and enabling, command registration and dispatch, saving worlds,
a backup of the live tree, and a complete offline restore across the server's own exit.

Nothing here downloads a server. The operator supplies the server files, the JDK and the plugin
JAR, and sets VAULTBACKUP_TEST_EULA_ACCEPTED=true only after accepting the Minecraft EULA
(https://www.minecraft.net/en-us/eula). No Minecraft client is used: menus, commands issued by a
player and tab completion are outside this run and stay unverified by it.

Environment:
  VAULTBACKUP_TEST_BASE     a directory that does not exist yet; fixtures, logs and results go there
  VAULTBACKUP_TEST_JAVA     java executable of the JDK the server is to run on (JDK 25)
  VAULTBACKUP_TEST_PLUGIN   the plugin JAR under test
  VAULTBACKUP_TEST_PAPER    directory with a Paper launcher JAR (paper-*.jar); cache/, libraries/
                            and versions/ beside it are copied too, so that nothing is downloaded
  VAULTBACKUP_TEST_SPIGOT   Spigot server JAR as built by the official BuildTools
  VAULTBACKUP_TEST_EULA_ACCEPTED=true
  VAULTBACKUP_TEST_PORT     optional first port (default 25610); each server uses ten ports from there,
                            and its RCON listens 100 above its game port. All on 127.0.0.1.

Usage: smoke_1_3.py [paper] [spigot]      (default: every server whose variable is set)
"""
from __future__ import annotations

import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import queue
import re
import secrets
import shutil
import signal
import socket
import struct
import subprocess
import sys
import threading
import time
import zipfile

ANSI = re.compile(r'\x1b\[[0-?]*[ -/]*[@-~]')
BACKUP_ID = r'(\d{8}T\d{9}Z-[0-9a-f]{12})'


def env(name):
    value = os.environ.get(name)
    if not value:
        raise SystemExit(f'Set {name} (see the top of this file)')
    return value


def stamp():
    return dt.datetime.now(dt.timezone.utc).isoformat(timespec='seconds')


def sha256(path):
    digest = hashlib.sha256()
    with Path(path).open('rb') as stream:
        while chunk := stream.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def properties(path):
    """A Java properties file as written by the plugin and its helper (no continuation lines)."""
    result = {}
    for line in Path(path).read_text(encoding='utf-8').splitlines():
        if line and not line.startswith(('#', '!')) and '=' in line:
            key, value = line.split('=', 1)
            result[key.strip()] = (value.replace('\\:', ':').replace('\\=', '=').replace('\\ ', ' ')
                                   .replace('\\\\', '\\'))
    return result


class Failure(AssertionError):
    pass


class Checks:
    """Every check is recorded. The first failure ends the scenario; the record is written all the same."""

    def __init__(self, server_name):
        self.server = server_name
        self.passed = []

    def ok(self, condition, label, detail=''):
        if not condition:
            raise Failure(f'{self.server}: {label}' + (f' ({detail})' if detail else ''))
        self.passed.append(label)
        print(f'  ok  {self.server}: {label}', flush=True)


class Server:
    """A server process with its console: send a command, wait for a log line."""

    def __init__(self, kind, directory, log_path, java):
        self.kind = kind
        self.directory = Path(directory)
        self.lines = []
        self.queue = queue.Queue()
        self.log_path = Path(log_path)
        if self.log_path.exists():
            raise Failure(f'refusing to replace a console log: {self.log_path}')
        self.log = self.log_path.open('w', encoding='utf-8')
        arguments = [java, '-Xms256M', '-Xmx1G', '-XX:ActiveProcessorCount=2', '-Dfile.encoding=UTF-8']
        self.process = subprocess.Popen(arguments + ['-jar', 'server.jar', '--nogui'], cwd=self.directory,
                                        stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                        text=True, encoding='utf-8', errors='replace', bufsize=1)
        self.log.write(f'TEST PROCESS [{stamp()}]: server PID {self.process.pid} in {self.directory}\n')

        def reader():
            for line in self.process.stdout:
                self.queue.put(line)
            self.queue.put(None)

        self.reader = threading.Thread(target=reader, daemon=True)
        self.reader.start()

    def read(self, timeout=1.0):
        try:
            line = self.queue.get(timeout=timeout)
        except queue.Empty:
            return None
        if line is not None:
            self.lines.append(ANSI.sub('', line))
            self.log.write(line)
            self.log.flush()
        return line

    def drain(self):
        while not self.queue.empty():
            self.read(0)

    def mark(self):
        self.drain()
        return len(self.lines)

    def expect(self, pattern, timeout=120, start=0):
        """The first line at or after index `start` that matches; reads on until one does."""
        deadline = time.monotonic() + timeout
        cursor = start
        compiled = re.compile(pattern)
        while True:
            for index in range(cursor, len(self.lines)):
                match = compiled.search(self.lines[index])
                if match:
                    return match
            cursor = len(self.lines)
            if time.monotonic() >= deadline:
                raise Failure(f'{self.kind}: no log line matching {pattern!r} within {timeout}s; see {self.log_path}')
            self.read()
            if self.process.poll() is not None and self.queue.empty() and cursor == len(self.lines):
                raise Failure(f'{self.kind}: the server exited while waiting for {pattern!r}; see {self.log_path}')

    def seen(self, pattern, start=0):
        self.drain()
        compiled = re.compile(pattern)
        return [line for line in self.lines[start:] if compiled.search(line)]

    def command(self, text):
        if self.process.poll() is not None:
            raise Failure(f'{self.kind}: cannot send {text!r}: the server has exited')
        start = self.mark()
        self.log.write(f'TEST COMMAND [{stamp()}]: {text}\n')
        self.log.flush()
        self.process.stdin.write(text + '\n')
        self.process.stdin.flush()
        return start

    def settle(self, seconds):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            self.read(0.2)

    def wait_exit(self, timeout=180):
        deadline = time.monotonic() + timeout
        while self.process.poll() is None and time.monotonic() < deadline:
            self.read()
        if self.process.poll() is None:
            raise Failure(f'{self.kind}: the server did not exit within {timeout}s; see {self.log_path}')
        self.reader.join(timeout=5)
        self.drain()
        self.log.write(f'TEST PROCESS [{stamp()}]: exit code {self.process.returncode}\n')
        self.log.close()
        return self.process.returncode

    def stop(self):
        """The administrator's own console stop."""
        self.command('stop')
        return self.wait_exit()

    def kill(self):
        self.process.send_signal(signal.SIGKILL)
        return self.wait_exit(60)


def rcon(port, password, command, timeout=20):
    """One command over the server's remote console; returns the text of the reply."""
    def packet(request, kind, body):
        payload = struct.pack('<ii', request, kind) + body.encode('utf-8') + b'\x00\x00'
        return struct.pack('<i', len(payload)) + payload

    def receive(connection):
        header = b''
        while len(header) < 4:
            chunk = connection.recv(4 - len(header))
            if not chunk:
                raise Failure('RCON connection closed')
            header += chunk
        length = struct.unpack('<i', header)[0]
        data = b''
        while len(data) < length:
            chunk = connection.recv(length - len(data))
            if not chunk:
                raise Failure('RCON connection closed')
            data += chunk
        request, kind = struct.unpack('<ii', data[:8])
        return request, kind, data[8:-2].decode('utf-8', errors='replace')

    with socket.create_connection(('127.0.0.1', port), timeout=timeout) as connection:
        connection.sendall(packet(7, 3, password))
        request, _, _ = receive(connection)
        if request != 7:
            raise Failure('RCON login refused')
        connection.sendall(packet(8, 2, command))
        _, _, body = receive(connection)
        return ANSI.sub('', re.sub('§.', '', body))


class Fixture:
    """One disposable server installation. The server root is <base>/<name>/server."""

    def __init__(self, base, kind, name, port, source, plugin):
        self.kind = kind
        self.home = base / name
        self.root = self.home / 'server'
        self.logs = base / 'logs'
        self.port = port
        self.rcon_port = port + 100
        self.rcon_password = secrets.token_hex(12)
        self.starts = 0
        self.root.mkdir(parents=True)
        self.logs.mkdir(exist_ok=True)
        source = Path(source)
        if kind == 'paper':
            launchers = sorted(source.glob('paper-*.jar'))
            if len(launchers) != 1:
                raise SystemExit(f'Expected exactly one paper-*.jar in {source}')
            self.server_jar = launchers[0]
            for extra in ('cache', 'libraries', 'versions'):
                if (source / extra).is_dir():
                    shutil.copytree(source / extra, self.root / extra)
        else:
            self.server_jar = source
        shutil.copy2(self.server_jar, self.root / 'server.jar')
        (self.root / 'plugins').mkdir()
        shutil.copy2(plugin, self.root / 'plugins' / 'VaultBackupPro.jar')
        if os.environ.get('VAULTBACKUP_TEST_EULA_ACCEPTED') != 'true':
            raise SystemExit('Read and accept the Minecraft EULA, then set VAULTBACKUP_TEST_EULA_ACCEPTED=true')
        (self.root / 'eula.txt').write_text('# The operator of this isolated test accepted the Minecraft EULA.\neula=true\n')
        self.write_server_properties()

    def write_server_properties(self):
        # An empty server pauses after 30 seconds here (the default is 60 on Spigot and "never" on Paper):
        # the automatic backup, the restore reservation and the stop all happen on a paused server.
        (self.root / 'server.properties').write_text(f'''server-ip=127.0.0.1
server-port={self.port}
online-mode=false
max-players=2
view-distance=2
simulation-distance=2
level-name=world
level-seed=1
level-type=minecraft:flat
generator-settings={{"biome":"minecraft:plains","layers":[{{"block":"minecraft:bedrock","height":1}},{{"block":"minecraft:dirt","height":2}},{{"block":"minecraft:grass_block","height":1}}]}}
generate-structures=false
spawn-protection=0
enable-query=false
enable-status=true
enable-rcon=true
rcon.port={self.rcon_port}
rcon.password={self.rcon_password}
broadcast-rcon-to-ops=false
motd=VaultBackup Pro disposable acceptance test
enforce-secure-profile=false
max-tick-time=180000
pause-when-empty-seconds=30
''')

    @property
    def backups(self):
        return self.home / 'VaultBackup-server-backups'

    @property
    def state(self):
        return self.home / 'VaultBackup-server-state'

    @property
    def config(self):
        return self.root / 'plugins' / 'VaultBackupPro' / 'config.yml'

    def start(self, java, phase):
        self.starts += 1
        return Server(self.kind, self.root, self.logs / f'{self.home.name}-{self.starts:02d}-{phase}.log', java)

    def active_job(self):
        active = self.state / 'active-restore.properties'
        if not active.is_file():
            return None
        return self.state / 'restore-jobs' / properties(active)['jobId']

    def job_status(self, job):
        return properties(job / 'status.properties').get('status', '')

    def wait_job(self, job, statuses, timeout=300):
        deadline = time.monotonic() + timeout
        status = ''
        while time.monotonic() < deadline:
            try:
                status = self.job_status(job)
            except (OSError, KeyError):
                status = ''
            if status in statuses:
                return status
            time.sleep(0.5)
        raise Failure(f'{self.kind}: restore job stayed at {status!r}, expected one of {sorted(statuses)}; see {job}')


def alive(pid):
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        return False
    except PermissionError:
        return True
    # A process that has exited but was not reaped yet still answers; its state says so.
    try:
        return Path(f'/proc/{pid}/stat').read_text().rsplit(')', 1)[1].split()[0] != 'Z'
    except OSError:
        return True


# The first line of /backup status, in either language: "<operation> / <progress> / <n> backups / free ...".
STATUS = r'\]: (?:\[[^\]]*\] )?(.+?) / .*? / (?:\d+ backups / free |バックアップ \d+ 件 / 空き )'
IDLE = ('Idle', '待機中')


def await_done(server, checks, label, idle=True):
    server.expect(r'Done \(', timeout=420)
    checks.ok(True, label)
    if idle:
        await_idle(server, 300)


def await_idle(server, timeout=120):
    """
    Commands are refused while another operation runs, and the list is not shown before the plugin has
    read (and verified) the archives once after a start. Wait for whatever is running to finish.
    """
    deadline = time.monotonic() + timeout
    while True:
        start = server.command('backup status')
        state = server.expect(STATUS, timeout=30, start=start).group(1)
        if state in IDLE:
            return
        if time.monotonic() >= deadline:
            raise Failure(f'{server.kind}: the plugin stayed busy ({state}) for {timeout}s; see {server.log_path}')
        time.sleep(1)


def setting(server, checks, command, key, label=None):
    await_idle(server)
    start = server.command(command)
    server.expect(r'Setting updated: ' + re.escape(key), timeout=30, start=start)
    if label:
        checks.ok(True, label)


def no_stack_trace(server, checks, phase):
    """An exception escaping from the plugin would show its classes in a stack trace, whatever the log level."""
    server.drain()
    traces = [line.strip() for line in server.lines
              if re.search(r'^\s*at com\.vaultbackup\.|Could not pass event|Error occurred while (?:enabling|disabling)|Unhandled exception', line)]
    checks.ok(not traces, f'no exception from the plugin in the console log ({phase})', '; '.join(traces[:3]))


def create_backup(server, checks, label, how='console', fixture=None):
    """Returns the ID of the backup that the server reports as created."""
    if how == 'console':
        start = server.command(f'backup create {label}')
        server.expect(r'Starting backup\. Saving worlds', timeout=30, start=start)
        server.expect(r'World save executed', timeout=120, start=start)
    else:
        # A remote console gets what is said while its command runs; the result is written to the server log.
        start = server.mark()
        reply = rcon(fixture.rcon_port, fixture.rcon_password, f'backup create {label}')
        checks.ok('Starting backup' in reply, f'RCON receives the acknowledgement of "backup create {label}"', reply)
    created = server.expect(r'Backup created: ' + BACKUP_ID, timeout=600, start=start)
    checks.ok(True, f'backup "{label}" is created from the {how}')
    return created.group(1)


def run(kind, fixture, java, plugin, results):
    checks = Checks(kind)
    record = {'server': kind, 'server_jar': fixture.server_jar.name, 'server_jar_sha256': sha256(fixture.server_jar),
              'plugin_sha256': sha256(plugin), 'started_at_utc': stamp(), 'passed': checks.passed, 'ok': False}
    results.append(record)
    server = None
    try:
        with zipfile.ZipFile(plugin) as archive:
            shipped_config = archive.read('config.yml')
            version = re.search(r"^version: '([^']+)'", archive.read('plugin.yml').decode('utf-8'), re.MULTILINE).group(1)
            bundled_helper = hashlib.sha256(archive.read('vaultbackup-restore.jar')).hexdigest()
        record['plugin_version'] = version

        # ---- first start, in the shipped configuration (Japanese)
        server = fixture.start(java, 'first-start')
        await_done(server, checks, 'the server starts with the plugin installed')
        enabled = server.expect(r'VaultBackup Pro ' + re.escape(version) + r' を起動しました（(.+?) / Minecraft ([0-9.]+)）', timeout=30)
        record['reported_server'] = enabled.group(1)
        record['reported_minecraft'] = enabled.group(2)
        checks.ok(True, f'the plugin reports itself enabled on {enabled.group(1)} / Minecraft {enabled.group(2)}')
        # By the plugin's own log prefixes and package, not by its name: a path may contain the name as well.
        problems = [line.strip() for line in server.lines
                    if re.search(r'\[VaultBackup(?:Pro)?\]|com\.vaultbackup', line) and re.search(r'ERROR|SEVERE|Exception|WARN', line)]
        checks.ok(not problems, 'enabling logs no warning or error about the plugin', '; '.join(problems[:3]))
        trouble = [line.strip() for line in server.lines if re.search(r'\bERROR\b|Exception in|Caused by:', line)]
        checks.ok(not trouble, 'the server log has no error at all after the first start', '; '.join(trouble[:3]))
        checks.ok(fixture.config.read_bytes() == shipped_config, 'the shipped config.yml is written unchanged')
        for directory in (fixture.backups, fixture.state):
            owner = directory / 'vaultbackup-owner.properties'
            checks.ok(owner.is_file() and Path(properties(owner)['serverRoot']) == fixture.root,
                      f'{directory.name} is created beside the server and records this server as its owner')
        helper = fixture.state / 'helper' / 'vaultbackup-restore.jar'
        checks.ok(helper.is_file() and sha256(helper) == bundled_helper, 'the bundled restore helper is installed outside the server root')
        start = server.command('backup help')
        server.expect(r'/backup create', timeout=20, start=start)
        checks.ok(True, 'the console can run /backup (help is shown in Japanese by default)')
        start = server.command('vaultbackup status')
        server.expect(r'自動|バックアップ', timeout=20, start=start)
        checks.ok(True, 'the alias /vaultbackup works')

        # ---- English from here on, for stable patterns
        text = fixture.config.read_text(encoding='utf-8')
        checks.ok('language: ja_JP' in text, 'fixture: the shipped language is ja_JP')
        fixture.config.write_text(text.replace('language: ja_JP', 'language: en_US'), encoding='utf-8')
        start = server.command('backup reload')
        server.expect(r'Configuration reloaded\.', timeout=30, start=start)
        checks.ok(True, 'an edited config.yml is applied by /backup reload')
        start = server.command('backup status')
        scheduled = server.expect(r'0 backups / free .* / automatic 60 minutes', timeout=20, start=start)
        checks.ok(bool(scheduled), '/backup status answers and shows the shipped schedule (automatic, every 60 minutes)')
        setting(server, checks, 'backup auto off', 'automatic.enabled', 'automatic backups can be switched off from the console')

        # ---- backups
        first = create_backup(server, checks, 'smoke')
        archive_path = fixture.backups / f'{first}.zip'
        checks.ok(archive_path.is_file() and (fixture.backups / f'{first}.properties').is_file(), 'the archive and its metadata are in the backup directory')
        with zipfile.ZipFile(archive_path) as archive:
            names = set(archive.namelist())
        record['first_backup_entries'] = len(names)
        checks.ok({'world/level.dat', 'server.properties', 'plugins/VaultBackupPro/config.yml'} <= names,
                  'the archive holds the world, the server settings and the plugin configuration')
        checks.ok(not any(name.endswith('session.lock') or name.startswith('logs/') or name.endswith('.log') for name in names),
                  'the archive holds no session.lock and no log')
        start = server.command('backup list')
        server.expect(re.escape(first), timeout=60, start=start)
        checks.ok(True, '/backup list shows the backup')
        start = server.command('backup verify')
        server.expect(r'Every archive was re-verified\. Valid backups: 1', timeout=300, start=start)
        checks.ok(True, '/backup verify re-reads the archive')

        reply = rcon(fixture.rcon_port, fixture.rcon_password, 'backup status')
        checks.ok('backups / free' in reply, 'the remote console can run /backup status', reply)
        second = create_backup(server, checks, 'remote', how='RCON', fixture=fixture)
        checks.ok(second != first, 'the remote console created a second backup')

        await_idle(server)
        start = server.command(f'backup delete {first}')
        server.expect(r'Backup deleted: ' + re.escape(first), timeout=120, start=start)
        checks.ok(not archive_path.exists(), '/backup delete removes the archive')

        # ---- settings written by commands
        setting(server, checks, 'backup interval 30', 'automatic.interval-minutes')
        written = fixture.config.read_text(encoding='utf-8')
        checks.ok('interval-minutes: 30' in written, 'a setting command writes its value to config.yml')
        checks.ok('# 表示言語' in written and '# 保存先変更には再起動が必要です。' in written, 'the comments of config.yml survive, Japanese included')
        setting(server, checks, 'backup retention count 5', 'retention.max-count', 'the retention setting is accepted')

        # ---- an automatic backup, on an empty (and by then paused) server
        setting(server, checks, 'backup interval 1', 'automatic.interval-minutes')
        start = server.mark()
        setting(server, checks, 'backup auto on', 'automatic.enabled')
        automatic = server.expect(r'Backup created: ' + BACKUP_ID, timeout=420, start=start)
        checks.ok(bool(server.seen(r'Server empty for \d+ seconds, pausing', start=0)[:1])
                  and not server.seen(r'Server empty for \d+ seconds, pausing', start=server.lines.index(automatic.string)),
                  'an automatic backup runs by itself, on a server that had paused because nobody was online')
        setting(server, checks, 'backup auto off', 'automatic.enabled')
        start = server.command('backup list')
        server.expect(re.escape(automatic.group(1)) + r'.*\| automatic', timeout=60, start=start)
        checks.ok(True, 'the automatic backup is listed with its label')

        # ---- the complete restore: reserve, stop, helper, start again
        restore_point = create_backup(server, checks, 'restorepoint')
        marker = fixture.root / 'written-after-the-backup.txt'
        marker.write_text('this file did not exist when the backup was made\n')
        start = server.command(f'backup restore {restore_point}')
        server.expect(r'Restore reserved: ' + re.escape(restore_point), timeout=300, start=start)
        job = fixture.active_job()
        checks.ok(job is not None and fixture.job_status(job) == 'WAITING_FOR_MANUAL_STOP', 'the restore is reserved and its helper waits')
        helper_pid = int(properties(job / 'helper-process.properties')['pid'])
        checks.ok(alive(helper_pid), 'the helper is a running process of its own')
        server.settle(3)
        checks.ok(server.process.poll() is None and marker.is_file(), 'reserving stops nothing and changes nothing')
        start = server.command('backup status')
        server.expect(re.escape(restore_point), timeout=20, start=start)
        stop_from = server.mark()
        code = server.stop()
        no_stack_trace(server, checks, 'first run and restore reservation')
        checks.ok(code == 0, 'the server stops normally', f'exit code {code}')
        stop_marker = properties(job / 'normal-stop.properties')
        record['stop_evidence'] = stop_marker.get('evidence')
        checks.ok(stop_marker.get('evidence') == ('SERVER_STOPPING' if kind == 'paper' else 'PLUGIN_DISABLED'),
                  'the stop is recorded with the evidence this server can give', str(stop_marker.get('evidence')))
        checks.ok(bool(server.seen(r'A restore is reserved', start=stop_from)), 'the log says before the exit that a restore will be applied')
        fixture.wait_job(job, {'SUCCESS'}, timeout=420)
        job_values = properties(job / 'job.properties')
        rollback = Path(job_values['rollback'])
        checks.ok(not marker.exists() and (rollback / marker.name).is_file(), 'the server data is the backup again, and the previous data is kept beside it')
        checks.ok((fixture.root / 'world' / 'level.dat').is_file() and not Path(job_values['stage']).exists(), 'the restored root is complete and no extraction is left')
        deadline = time.monotonic() + 30
        while alive(helper_pid) and time.monotonic() < deadline:
            time.sleep(0.2)
        checks.ok(not alive(helper_pid), 'the helper has exited')

        server = fixture.start(java, 'after-restore')
        await_done(server, checks, 'the server starts on the restored data')
        server.expect(r'RESTORE VERIFIED SUCCESS', timeout=30)
        server.expect(r'Backup: ' + re.escape(restore_point), timeout=10)
        checks.ok(fixture.active_job() is None, 'the verified restore is reported once at the next start')
        start = server.command('backup list')
        server.expect(re.escape(restore_point), timeout=120, start=start)
        checks.ok(True, 'the plugin works after the restore')

        # ---- a cancelled reservation is never applied
        keep = fixture.root / 'kept-after-cancel.txt'
        keep.write_text('still here\n')
        start = server.command(f'backup restore {restore_point}')
        server.expect(r'Restore reserved: ' + re.escape(restore_point), timeout=300, start=start)
        job = fixture.active_job()
        start = server.command('backup restore cancel')
        server.expect(r'Restore reservation cancelled', timeout=60, start=start)
        checks.ok(fixture.active_job() is None, 'cancelling clears the reservation')
        code = server.stop()
        no_stack_trace(server, checks, 'after the restore')
        time.sleep(4)
        checks.ok(code == 0 and keep.is_file() and fixture.job_status(job) == 'CANCELLED', 'a cancelled reservation is not applied when the server stops')

        # ---- /bukkit:reload: the server keeps running, the reservation is withdrawn
        server = fixture.start(java, 'reload')
        await_done(server, checks, 'the server starts again')
        start = server.command(f'backup restore {restore_point}')
        server.expect(r'Restore reserved: ' + re.escape(restore_point), timeout=300, start=start)
        job = fixture.active_job()
        # The plugin reload of Bukkit. On Paper the bare "reload" is the vanilla command (data packs only).
        start = server.command('bukkit:reload confirm')
        server.expect(r'Plugin reload detected', timeout=180, start=start)
        server.expect(r'A restore reservation left from before this start was cancelled', timeout=60, start=start)
        server.settle(5)
        checks.ok(server.process.poll() is None, 'reloading the plugins (/bukkit:reload confirm) does not stop the server')
        checks.ok(fixture.active_job() is None and fixture.job_status(job) == 'CANCELLED', 'the reservation made before the reload is withdrawn')
        after_reload = create_backup(server, checks, 'afterreload')
        checks.ok(bool(after_reload), 'commands and backups work after the reload')
        code = server.stop()
        no_stack_trace(server, checks, 'reload')
        time.sleep(4)
        checks.ok(code == 0 and keep.is_file(), 'nothing is restored when the reloaded server stops')

        # ---- the server is killed while a restore is reserved: nothing is applied, and the next start says so
        server = fixture.start(java, 'kill')
        await_done(server, checks, 'the server starts again')
        start = server.command(f'backup restore {restore_point}')
        server.expect(r'Restore reserved: ' + re.escape(restore_point), timeout=300, start=start)
        job = fixture.active_job()
        server.settle(2)
        server.kill()
        no_stack_trace(server, checks, 'the killed server')
        fixture.wait_job(job, {'FAILED_BEFORE_SWAP'}, timeout=120)
        checks.ok(keep.is_file() and not (job / 'normal-stop.properties').exists(), 'a killed server is not restored over')
        server = fixture.start(java, 'after-kill')
        await_done(server, checks, 'the server starts after having been killed')
        server.expect(r'was not applied', timeout=30)
        checks.ok(fixture.active_job() is None, 'the unapplied restore is reported and cleared at the next start')
        start = server.command('backup status')
        server.expect(r'backups / free ', timeout=20, start=start)
        code = server.stop()
        no_stack_trace(server, checks, 'after the kill')
        checks.ok(code == 0, 'and the plugin is usable again')
        record['restore_point'] = restore_point
        record['ok'] = True
    except Failure as failure:
        record['failure'] = str(failure)
        print(f'FAILED {failure}', flush=True)
    finally:
        record['finished_at_utc'] = stamp()
        if server is not None and server.process.poll() is None:
            try:
                server.stop()
            except Exception as cleanup:  # noqa: BLE001 - the fixture is disposable; the record matters
                record['cleanup_error'] = str(cleanup)
                server.process.kill()
    return record


def ended_together(kind, fixture, java, results, restore_point):
    """
    A container stop and "systemctl stop" end every process of the service at once, the helper included.
    Nothing is restored then. The documented way out is to run helpers/restore.sh by hand for the
    reserved job before the server is started again.
    """
    checks = Checks(f'{kind}/by-hand')
    record = {'server': kind, 'scenario': 'the helper is ended together with the server; the reserved restore is then run by hand',
              'passed': checks.passed, 'ok': False}
    results.append(record)
    script = Path(__file__).resolve().parents[2] / 'helpers' / 'restore.sh'
    server = None
    try:
        checks.ok(script.is_file(), 'fixture: helpers/restore.sh of this source tree')
        server = fixture.start(java, 'ended-together')
        await_done(server, checks, 'the server starts')
        marker = fixture.root / 'written-before-the-restore-by-hand.txt'
        marker.write_text('to be replaced by the restore\n')
        start = server.command(f'backup restore {restore_point}')
        server.expect(r'Restore reserved: ' + re.escape(restore_point), timeout=300, start=start)
        job = fixture.active_job()
        checks.ok(job is not None, 'the restore is reserved')
        helper_pid = int(properties(job / 'helper-process.properties')['pid'])
        os.kill(helper_pid, signal.SIGTERM)
        server.process.send_signal(signal.SIGTERM)
        code = server.wait_exit()
        no_stack_trace(server, checks, 'ended by a termination signal')
        checks.ok(code in (0, 143), 'the server shuts down on the termination signal', f'exit code {code}')
        deadline = time.monotonic() + 30
        while alive(helper_pid) and time.monotonic() < deadline:
            time.sleep(0.2)
        checks.ok(not alive(helper_pid), 'the helper was ended at the same time')
        time.sleep(3)
        checks.ok(marker.is_file() and fixture.job_status(job) == 'WAITING_FOR_MANUAL_STOP', 'nothing was applied, and the reservation is still there')
        environment = dict(os.environ, VAULTBACKUP_HELPER_JAR=str(fixture.state / 'helper' / 'vaultbackup-restore.jar'),
                           JAVA_HOME=str(Path(java).resolve().parents[1]))
        by_hand = subprocess.run(['sh', str(script), str(job / 'job.properties')], env=environment, capture_output=True,
                                 text=True, encoding='utf-8', errors='replace', timeout=900)
        (fixture.logs / f'{fixture.home.name}-restore-by-hand.log').write_text(by_hand.stdout + by_hand.stderr, encoding='utf-8')
        checks.ok(by_hand.returncode == 0 and fixture.job_status(job) == 'SUCCESS', 'helpers/restore.sh applies the reserved restore',
                  f'exit code {by_hand.returncode}, status {fixture.job_status(job)}')
        rollback = Path(properties(job / 'job.properties')['rollback'])
        checks.ok(not marker.exists() and (rollback / marker.name).is_file(), 'the server data is the backup, and the previous data is kept beside it')
        server = fixture.start(java, 'after-restore-by-hand')
        await_done(server, checks, 'the server starts on the restored data')
        server.expect(r'RESTORE VERIFIED SUCCESS', timeout=30)
        checks.ok(fixture.active_job() is None, 'and reports the verified restore')
        code = server.stop()
        no_stack_trace(server, checks, 'after the restore by hand')
        checks.ok(code == 0, 'it stops normally')
        record['ok'] = True
    except Failure as failure:
        record['failure'] = str(failure)
        print(f'FAILED {failure}', flush=True)
    finally:
        if server is not None and server.process.poll() is None:
            server.process.kill()
    return record


def shared_storage(base, source, java, plugin, owner, results):
    """A second server that names the first server's directories is refused: its plugin, not the server."""
    checks = Checks('paper/second-server')
    record = {'server': 'paper', 'scenario': 'a second server names the first server\'s storage', 'passed': checks.passed, 'ok': False}
    results.append(record)
    fixture = Fixture(base, 'paper', 'paper-second', owner.port + 1, source, plugin)
    settings = fixture.root / 'plugins' / 'VaultBackupPro'
    settings.mkdir()
    with zipfile.ZipFile(plugin) as archive:
        text = archive.read('config.yml').decode('utf-8')
    text = (text.replace('language: ja_JP', 'language: en_US')
            .replace('backup-directory: ../VaultBackup-{server}-backups', f'backup-directory: {owner.backups}')
            .replace('state-directory: ../VaultBackup-{server}-state', f'state-directory: {owner.state}')
            .replace('helper-jar: ../VaultBackup-{server}-state/helper/vaultbackup-restore.jar',
                     f'helper-jar: {owner.state}/helper/vaultbackup-restore.jar'))
    (settings / 'config.yml').write_text(text, encoding='utf-8')
    server = None
    try:
        before = sorted(path.name for path in owner.backups.iterdir())
        server = fixture.start(java, 'shared-storage')
        await_done(server, checks, 'the second server starts', idle=False)
        server.expect(r'is used by another server', timeout=30)
        server.expect(r'VaultBackup was disabled', timeout=30)
        server.settle(5)
        checks.ok(server.process.poll() is None, 'the second server keeps running; only the plugin is disabled')
        checks.ok(sorted(path.name for path in owner.backups.iterdir()) == before, 'the first server\'s backups are untouched')
        checks.ok(Path(properties(owner.state / 'vaultbackup-owner.properties')['serverRoot']) == owner.root, 'the ownership record still names the first server')
        code = server.stop()
        checks.ok(code == 0, 'the second server stops normally')
        record['ok'] = True
    except Failure as failure:
        record['failure'] = str(failure)
        print(f'FAILED {failure}', flush=True)
    finally:
        if server is not None and server.process.poll() is None:
            server.process.kill()
    return record


def terminal_session(fixture, java, results, restore_point):
    """The server is the only program of a tmux session, which therefore ends with it and hangs up what it started."""
    checks = Checks('paper/tmux')
    record = {'server': 'paper', 'scenario': 'restore of a server that runs as the only program of a tmux session', 'passed': checks.passed, 'ok': False}
    results.append(record)
    if not shutil.which('tmux'):
        record['skipped'] = 'tmux is not installed'
        return record
    session = 'vaultbackup-acceptance'
    latest = fixture.root / 'logs' / 'latest.log'

    def tmux(*arguments):
        return subprocess.run(['tmux', *arguments], capture_output=True, text=True)

    def wait_log(pattern, timeout, since=0):
        """`since` is a byte offset into the server's own log file."""
        deadline = time.monotonic() + timeout
        compiled = re.compile(pattern)
        while time.monotonic() < deadline:
            if latest.is_file():
                match = compiled.search(latest.read_bytes()[since:].decode('utf-8', errors='replace'))
                if match:
                    return match
            time.sleep(0.5)
        raise Failure(f'paper/tmux: no log text matching {pattern!r} within {timeout}s; see {latest}')

    def wait_idle(timeout=300):
        deadline = time.monotonic() + timeout
        while True:
            offset = latest.stat().st_size
            tmux('send-keys', '-t', session, 'backup status', 'Enter')
            state = wait_log(STATUS, 30, since=offset).group(1)
            if state in IDLE:
                return
            if time.monotonic() >= deadline:
                raise Failure(f'paper/tmux: the plugin stayed busy ({state}) for {timeout}s; see {latest}')
            time.sleep(1)

    try:
        if latest.exists():
            latest.unlink()
        marker = fixture.root / 'written-before-the-tmux-restore.txt'
        marker.write_text('to be replaced by the restore\n')
        tmux('kill-session', '-t', session)
        command = f'cd {fixture.root} && exec {java} -Xms256M -Xmx1G -XX:ActiveProcessorCount=2 -Dfile.encoding=UTF-8 -jar server.jar --nogui'
        started = tmux('new-session', '-d', '-s', session, command)
        checks.ok(started.returncode == 0, 'the server is started as the only program of a tmux session', started.stderr.strip())
        wait_log(r'Done \(', 420)
        wait_idle()
        tmux('send-keys', '-t', session, f'backup restore {restore_point}', 'Enter')
        wait_log(r'Restore reserved: ' + re.escape(restore_point), 300)
        job = fixture.active_job()
        checks.ok(job is not None, 'the restore is reserved from the console inside tmux')
        helper_pid = int(properties(job / 'helper-process.properties')['pid'])
        tmux('send-keys', '-t', session, 'stop', 'Enter')
        deadline = time.monotonic() + 180
        while tmux('has-session', '-t', session).returncode == 0 and time.monotonic() < deadline:
            time.sleep(0.5)
        checks.ok(tmux('has-session', '-t', session).returncode != 0, 'the session ends with the server')
        fixture.wait_job(job, {'SUCCESS'}, timeout=420)
        checks.ok(not marker.exists(), 'the helper outlived the terminal it shared with the server, and the restore was applied')
        deadline = time.monotonic() + 30
        while alive(helper_pid) and time.monotonic() < deadline:
            time.sleep(0.2)
        checks.ok(not alive(helper_pid), 'the helper has exited')
        record['ok'] = True
    except Failure as failure:
        record['failure'] = str(failure)
        print(f'FAILED {failure}', flush=True)
    finally:
        tmux('kill-session', '-t', session)
    return record


def main():
    kinds = [argument for argument in sys.argv[1:] if argument in ('paper', 'spigot')]
    if len(kinds) != len(sys.argv) - 1:
        raise SystemExit(__doc__)
    if not kinds:
        kinds = [kind for kind, name in (('paper', 'VAULTBACKUP_TEST_PAPER'), ('spigot', 'VAULTBACKUP_TEST_SPIGOT')) if os.environ.get(name)]
    if not kinds:
        raise SystemExit(__doc__)
    base = Path(env('VAULTBACKUP_TEST_BASE')).resolve()
    if base.exists():
        raise SystemExit(f'{base} exists already; every run needs a new directory')
    java = env('VAULTBACKUP_TEST_JAVA')
    plugin = Path(env('VAULTBACKUP_TEST_PLUGIN')).resolve()
    if os.environ.get('VAULTBACKUP_TEST_EULA_ACCEPTED') != 'true':
        raise SystemExit('Read and accept the Minecraft EULA, then set VAULTBACKUP_TEST_EULA_ACCEPTED=true')
    base.mkdir(parents=True)
    results = []
    summary = {'started_at_utc': stamp(), 'java': subprocess.run([java, '-version'], capture_output=True, text=True).stderr.splitlines()[0],
               'plugin': plugin.name, 'plugin_sha256': sha256(plugin), 'results': results}
    for index, kind in enumerate(kinds):
        source = Path(env('VAULTBACKUP_TEST_PAPER' if kind == 'paper' else 'VAULTBACKUP_TEST_SPIGOT')).resolve()
        fixture = Fixture(base, kind, kind, int(os.environ.get('VAULTBACKUP_TEST_PORT', '25610')) + index * 10, source, plugin)
        record = run(kind, fixture, java, plugin, results)
        if record['ok']:
            ended_together(kind, fixture, java, results, record['restore_point'])
        if kind == 'paper' and record['ok']:
            shared_storage(base, source, java, plugin, fixture, results)
            terminal_session(fixture, java, results, record['restore_point'])
    summary['finished_at_utc'] = stamp()
    summary['ok'] = all(result['ok'] or 'skipped' in result for result in results)
    summary['checks_passed'] = sum(len(result['passed']) for result in results)
    (base / 'summary.json').write_text(json.dumps(summary, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')
    print(json.dumps({'ok': summary['ok'], 'checks_passed': summary['checks_passed'],
                      'failures': [result['failure'] for result in results if 'failure' in result]}, ensure_ascii=False, indent=1))
    return 0 if summary['ok'] else 1


if __name__ == '__main__':
    sys.exit(main())
