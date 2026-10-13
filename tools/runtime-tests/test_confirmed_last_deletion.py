"""Runtime regression for the final pinned 1.1.0 release.

Usage: python test_confirmed_last_deletion.py EXPECTED_SHA256 [variant ...]
Only use authorized disposable fixtures. See README.md. All destructive operations,
permission-failure injection, and file moves are scoped to the NEW run directory.
"""
from confirmed_delete_harness import *
import concurrent.futures
import sys
import traceback

CATALOGS = {}
ID_PATTERN = r'(\d{8}T\d{9}Z-[0-9a-f]{12})'


def load_messages(pin):
    with zipfile.ZipFile(pin) as jar:
        for lang in ('ja_JP', 'en_US'):
            CATALOGS[lang] = dict(line.split('=', 1) for line in jar.read('messages/' + lang + '.properties').decode().splitlines()
                                  if line and not line.startswith('#') and '=' in line)


def pattern(key, lang='ja_JP'):
    value = CATALOGS[lang][key].split('\\n')[0]
    tokens = re.split(r'(\{\d+\})', value)
    return ''.join('.+?' if re.fullmatch(r'\{\d+\}', t) else re.escape(t) for t in tokens)


def expect_key(server, key, lang='ja_JP', start=0, timeout=120):
    return server.expect(pattern(key, lang), timeout=timeout, start=start)


def command_key(server, command, key, lang='ja_JP', timeout=120):
    return expect_key(server, key, lang, server.cmd(command), timeout)


def idle(server, lang='ja_JP', timeout=90):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        line = command_key(server, 'backup status', 'status.summary', lang, 20)
        if CATALOGS[lang]['operation.idle'] in ANSI.sub('', line):
            return line
        time.sleep(.3)
    raise TimeoutError('Plugin did not become idle')


def catalogue(server, expected, lang='ja_JP'):
    idle(server, lang)
    line = command_key(server, 'backup status', 'status.summary', lang)
    count = re.search(r'バックアップ (\d+) 件' if lang == 'ja_JP' else r'/ (\d+) backups /', ANSI.sub('', line))
    assert count and int(count.group(1)) == expected, line
    start = server.cmd('backup list')
    expect_key(server, 'list.empty' if expected == 0 else 'list.title', lang, start)
    idle(server, lang)
    return ANSI.sub('', line).strip()


def create(server, label, lang='ja_JP'):
    idle(server, lang)
    start = server.cmd('backup create ' + label)
    line = server.expect('(?:' + pattern('backup.created', lang) + ')|(?:' + pattern('job.failed', lang) + ')', timeout=240, start=start)
    if re.search(pattern('job.failed', lang), ANSI.sub('', line)):
        raise RuntimeError('Backup creation failed; inspect preserved fixture console: ' + ANSI.sub('', line).strip())
    backup_id = re.search(ID_PATTERN, line).group(1)
    idle(server, lang)
    return backup_id


def archives(variant):
    return ROOT / (variant + '-backups')


def exists(variant, backup_id):
    return all((archives(variant) / (backup_id + suffix)).is_file() for suffix in ('.zip', '.properties'))


def warning_key():
    # Pin this key after implementation finishes. Refuse a release missing its warning.
    return 'delete.last.warning'


def preview_delete(server, backup_id, last, lang='ja_JP'):
    start = server.cmd('backup delete ' + backup_id)
    line = expect_key(server, 'delete.preview', lang, start)
    idle(server, lang)
    server.drain()
    segment = ''.join(server.lines[start:])
    has_warning = re.search(pattern(warning_key(), lang), ANSI.sub('', segment)) is not None
    assert has_warning == last, ('Last-good warning mismatch', last, segment)
    return ANSI.sub('', segment).strip()


def confirmed_delete(server, variant, backup_id, last, lang='ja_JP'):
    preview = preview_delete(server, backup_id, last, lang)
    command_key(server, 'backup delete ' + backup_id + ' confirm', 'delete.done', lang, 180)
    idle(server, lang)
    assert not (archives(variant) / (backup_id + '.zip')).exists()
    assert not (archives(variant) / (backup_id + '.properties')).exists()
    return preview


def clean_expected_protections(server, start=0):
    segment = ANSI.sub('', ''.join(server.lines[start:]))
    assert 'com.vaultbackup.' not in segment, 'Expected protection emitted a stack trace'
    assert not re.search(r'\[(?:VaultBackupPro|VaultBackup)\].*(?:SEVERE|ERROR)', segment), segment
    assert not any(text in segment for text in ('Backup deletion failed.', 'バックアップ削除に失敗しました'))


def world_time(server):
    start = server.cmd('execute in minecraft:overworld run time query gametime')
    line = server.expect(r'The (?:game )?time is \d+', timeout=20, start=start)
    return int(re.search(r'The (?:game )?time is (\d+)', ANSI.sub('', line)).group(1))


def cancel_restore(server, variant, job, lang='ja_JP'):
    command_key(server, 'backup restore cancel', 'restore.cancelled', lang, 40)
    wait_status(job, {'CANCELLED'}, timeout=40)
    idle(server, lang)


def reserve(server, variant, backup_id, lang='ja_JP'):
    command_key(server, 'backup restore ' + backup_id, 'restore.preview', lang, 180)
    idle(server, lang)
    command_key(server, 'backup restore ' + backup_id + ' confirm', 'restore.reserved', lang, 180)
    job, active = active_job(variant)
    assert active['backupId'] == backup_id
    wait_status(job, {'WAITING_FOR_MANUAL_STOP'}, timeout=30)
    return job


def cleanup(server, variant, lang='ja_JP'):
    if server is None or server.closed or server.p.poll() is not None:
        return 'already stopped'
    if not any('Done (' in line for line in server.lines):
        server.expect(r'Done \(', timeout=180)
    state = ROOT / (variant + '-state') / 'active-restore.properties'
    if state.exists():
        job, unused = active_job(variant)
        status = props(job / 'status.properties').get('status')
        if status in {'COMMITTED', 'READY', 'WAITING_FOR_MANUAL_STOP', 'CANCEL_PENDING'}:
            cancel_restore(server, variant, job, lang)
    return server.finish()


def run_cycle(variant, pin, expected_hash):
    result = {'variant': variant, 'tested_jar_sha256': expected_hash, 'started_at_utc': stamp(), 'checks': []}
    server = None
    try:
        marker = prepare_fixture(variant, pin)
        directory = ROOT / variant
        properties = directory / 'server.properties'
        text = properties.read_text()
        text = re.sub(r'(?m)^pause-when-empty-seconds=.*$', 'pause-when-empty-seconds=-1', text)
        properties.write_text(text)
        result['server_jar_sha256'] = sha256(directory / 'server.jar')
        result['fixture_only_exclusions'] = ['plugins/spark/tmp/**']
        server = Server(variant, 'japanese-delete-restore')
        server.expect(r'Done \(', timeout=180)
        idle(server)
        assert any('VaultBackup Pro 1.1.0' in line for line in server.lines), '1.1.0 startup not observed'
        command_key(server, 'backup help', 'command.help.title')
        catalogue(server, 0)
        result['checks'] += ['final_1_1_0_enabled', 'japanese_help_status_empty_list']
        first = create(server, 'first-delete-fixture')
        start = server.cmd('backup delete ' + first + ' confirm')
        expect_key(server, 'delete.request', start=start)
        assert exists(variant, first)
        result['checks'].append('direct_confirmation_without_preview_refused')
        second = create(server, 'second-delete-fixture')
        catalogue(server, 2)
        result['two_to_one_preview'] = confirmed_delete(server, variant, first, False)
        catalogue(server, 1)
        result['last_delete_warning'] = confirmed_delete(server, variant, second, True)
        result['zero_status'] = catalogue(server, 0)
        result['checks'] += ['confirmed_nonlast_delete_two_to_one', 'japanese_last_good_warning_before_confirmation',
                             'confirmed_last_delete_one_to_zero', 'zero_status_and_empty_list_truthful']
        restored = create(server, 'recreate-after-zero')
        catalogue(server, 1)
        result['archive'] = zipcheck(archives(variant) / (restored + '.zip'), marker, expected_hash)
        result['checks'] += ['create_after_zero_succeeds', 'full_archive_manifest_crc_sha256_and_embedded_jar_verified']
        mutation = ('Changed after 1.1.0 backup: ' + variant + '\n').encode()
        (directory / 'vaultbackup-fixture.txt').write_bytes(mutation)
        # A deletion preview cannot survive a later restore reservation.
        preview_delete(server, restored, True)
        job = reserve(server, variant, restored)
        protection_start = server.cmd('backup delete ' + restored + ' confirm')
        expect_key(server, 'delete.protected', start=protection_start)
        command_key(server, 'backup delete ' + restored, 'delete.protected')
        command_key(server, 'backup create blocked-reservation', 'backup.blocked')
        assert exists(variant, restored)
        if variant == 'paper-26.2':
            cancel_restore(server, variant, job)
            assert not (job / 'receipt.properties').exists()
            assert (directory / 'vaultbackup-fixture.txt').read_bytes() == mutation
            command_key(server, 'backup restore ' + restored + ' confirm', 'restore.noconfirmation')
            result['checks'].append('japanese_confirmed_restore_cancellation_and_stale_restore_confirm_refusal')
            job = reserve(server, variant, restored)
            result['checks'].append('same_jvm_restore_rereservation_after_cancellation')
        before = world_time(server)
        server.stay_alive(5)
        after = world_time(server)
        assert after > before
        job_properties = props(job / 'job.properties')
        assert not (job / 'normal-stop.properties').exists()
        assert not (job / 'receipt.properties').exists()
        assert not Path(job_properties['stage']).exists()
        assert not Path(job_properties['rollback']).exists()
        assert (directory / 'vaultbackup-fixture.txt').read_bytes() == mutation
        clean_expected_protections(server)
        result['checks'] += ['restore_reserved_last_backup_preview_and_confirm_protected', 'create_while_reserved_blocked',
                             'expected_protections_without_plugin_stacktrace', 'world_ticks_and_jvm_alive_after_reservation',
                             'no_stage_swap_or_receipt_before_manual_stop']
        result['restore_shutdown_exit'] = server.finish()
        server = None
        wait_status(job, {'SUCCESS'})
        assert (job / 'normal-stop.properties').is_file()
        assert (job / 'receipt.properties').is_file()
        assert (directory / 'vaultbackup-fixture.txt').read_bytes() == marker
        rollback = Path(job_properties['rollback'])
        assert (rollback / 'vaultbackup-fixture.txt').read_bytes() == mutation
        port = int(props(directory / 'server.properties')['server-port'])
        with socket.socket() as sock:
            sock.settimeout(.5)
            assert sock.connect_ex(('127.0.0.1', port)) != 0
        server = Server(variant, 'japanese-restart-receipt')
        receipt = expect_key(server, 'startup.success', timeout=180)
        server.expect(r'Done \(', timeout=180)
        assert foreground_before(''.join(server.lines), '復元成功を検証しました') in {32, 92}
        assert (job / 'receipt-consumed.properties').is_file()
        assert sha256(directory / 'plugins/VaultBackupPro-1.1.0.jar') == expected_hash
        catalogue(server, 1)
        result['japanese_receipt'] = ANSI.sub('', receipt).strip()
        result['checks'] += ['offline_restore_after_manual_stop', 'original_mutation_preserved_in_rollback',
                             'no_automatic_restart', 'manual_restart_green_japanese_success_receipt',
                             'receipt_consumed_and_restored_final_jar_hash_verified']
        result['final_stop_exit'] = server.finish()
        server = None
        result['passed'] = True
    except Exception as error:
        result['passed'] = False
        result['error'] = str(error)
        traceback.print_exc()
        try:
            result['failure_cleanup'] = cleanup(server, variant)
        except Exception as cleanup_error:
            result['failure_cleanup_error'] = str(cleanup_error)
    result['completed_at_utc'] = stamp()
    write_json(LOGS / (variant + '-result.json'), result)
    print('CYCLE RESULT', json.dumps(result, ensure_ascii=False), flush=True)
    return result


def english_extra(pin, expected_hash):
    variant = 'paper-26.2'
    result = {'variant': variant, 'language': 'en_US', 'tested_jar_sha256': expected_hash,
              'started_at_utc': stamp(), 'checks': []}
    server = None
    set_language(variant, 'language', 'en_US')
    try:
        server = Server(variant, 'english-edge-cases')
        server.expect(r'Done \(', timeout=180)
        idle(server, 'en_US')
        command_key(server, 'backup help', 'command.help.title', 'en_US')
        first = sorted(archives(variant).glob('*.zip'))[0].stem
        command_key(server, 'backup delete ' + first + ' confirm', 'delete.request', 'en_US')
        preview_delete(server, first, True, 'en_US')
        server.stay_alive(16)
        command_key(server, 'backup delete ' + first + ' confirm', 'delete.request', 'en_US')
        assert exists(variant, first)
        result['checks'] += ['english_help_and_direct_confirm_refusal', 'expired_last_delete_confirmation_refused']
        result['english_last_delete_warning'] = confirmed_delete(server, variant, first, True, 'en_US')
        result['english_zero_status'] = catalogue(server, 0, 'en_US')
        first = create(server, 'english-recreated', 'en_US')
        second = create(server, 'stale-sibling', 'en_US')
        preview_delete(server, first, False, 'en_US')
        # Model another administrator changing the store after preview. Move only
        # this generated sibling to an isolated quarantine; never touch baseline data.
        quarantine = ROOT / 'stale-fixture-quarantine'
        quarantine.mkdir(exist_ok=True)
        for suffix in ('.zip', '.properties'):
            (archives(variant) / (second + suffix)).rename(quarantine / (second + suffix))
        start = server.cmd('backup delete ' + first + ' confirm')
        expect_key(server, 'delete.last.changed', 'en_US', start)
        expect_key(server, warning_key(), 'en_US', start)
        idle(server, 'en_US')
        assert exists(variant, first), 'Stale non-last confirmation deleted the now-last archive'
        result['checks'] += ['english_last_delete_zero_and_recreate', 'changed_lastness_requires_fresh_warning_and_confirmation']
        # The refusal installs a fresh warned pending confirmation. Only this
        # explicit second confirmation is authorized to delete the now-last item.
        command_key(server, 'backup delete ' + first + ' confirm', 'delete.done', 'en_US', 180)
        idle(server, 'en_US')
        assert not exists(variant, first)
        catalogue(server, 0, 'en_US')
        first = create(server, 'expected-io-fixture', 'en_US')
        clean_expected_protections(server)
        result['checks'].append('expected_expiry_stale_and_protection_paths_have_no_stacktrace')
        preview_delete(server, first, True, 'en_US')
        directory = archives(variant)
        mode = directory.stat().st_mode & 0o777
        start = len(server.lines)
        try:
            directory.chmod(0o555)
            start = server.cmd('backup delete ' + first + ' confirm')
            expect_key(server, 'job.failed', 'en_US', start)
            server.expect(r'AccessDeniedException', timeout=30, start=start)
            idle(server, 'en_US')
        finally:
            directory.chmod(mode)
        assert exists(variant, first), 'Fault injection removed fixture archive'
        server.drain()
        fault = ANSI.sub('', ''.join(server.lines[start:]))
        assert 'AccessDeniedException' in fault and 'com.vaultbackup.' in fault
        result['checks'].append('unexpected_io_failure_retains_diagnostic_and_stacktrace')
        # The minimum byte limit cannot retain zero valid backups automatically.
        command_key(server, 'backup retention count 1', 'settings.updated', 'en_US')
        command_key(server, 'backup retention bytes 1', 'settings.updated', 'en_US')
        newest = create(server, 'retention-one', 'en_US')
        catalogue(server, 1, 'en_US')
        assert exists(variant, newest) and not exists(variant, first)
        assert (archives(variant) / (newest + '.zip')).stat().st_size > 1
        result['checks'].append('automatic_retention_keeps_newest_one_even_over_byte_limit')
        command_key(server, 'backup interval 1', 'settings.updated', 'en_US')
        command_key(server, 'backup auto on', 'settings.updated', 'en_US')
        began = time.monotonic()
        line = expect_key(server, 'backup.created', 'en_US', len(server.lines), 150)
        automatic = re.search(ID_PATTERN, line).group(1)
        elapsed = time.monotonic() - began
        assert elapsed >= 58
        idle(server, 'en_US')
        command_key(server, 'backup auto off', 'settings.updated', 'en_US')
        assert props(archives(variant) / (automatic + '.properties'))['label'] == 'automatic'
        catalogue(server, 1, 'en_US')
        assert exists(variant, automatic) and not exists(variant, newest)
        result['automatic_elapsed_seconds'] = elapsed
        result['checks'].append('real_one_minute_automatic_backup_and_retention_still_keep_one')
        command_key(server, 'backup retention count 20', 'settings.updated', 'en_US')
        command_key(server, 'backup retention bytes 0', 'settings.updated', 'en_US')
        job = reserve(server, variant, automatic, 'en_US')
        command_key(server, 'backup delete ' + automatic, 'delete.protected', 'en_US')
        cancel_restore(server, variant, job, 'en_US')
        command_key(server, 'backup restore ' + automatic + ' confirm', 'restore.noconfirmation', 'en_US')
        assert not (job / 'receipt.properties').exists()
        catalogue(server, 1, 'en_US')
        result['checks'].append('english_restore_reservation_protection_cancellation_and_stale_confirm_refusal')
        result['final_stop_exit'] = server.finish()
        server = None
        result['passed'] = True
    except Exception as error:
        result['passed'] = False
        result['error'] = str(error)
        traceback.print_exc()
        try:
            result['failure_cleanup'] = cleanup(server, variant, 'en_US')
        except Exception as cleanup_error:
            result['failure_cleanup_error'] = str(cleanup_error)
    result['completed_at_utc'] = stamp()
    result['gui_limit'] = 'No interactive Minecraft client; GUI cancellation and clicks require separate testing.'
    write_json(LOGS / 'english-edge-cases-result.json', result)
    print('ENGLISH RESULT', json.dumps(result, ensure_ascii=False), flush=True)
    return result


if __name__ == '__main__':
    assert len(sys.argv) >= 2, 'Supply independently confirmed final artifact SHA-256'
    expected = sys.argv[1]
    pin, inspection, config = pin_artifact(expected)
    load_messages(pin)
    for lang in ('ja_JP', 'en_US'):
        assert warning_key() in CATALOGS[lang], 'Missing explicit last-good deletion warning'
    variants = sys.argv[2:] or VARIANTS
    assert set(variants) <= set(VARIANTS), variants
    results = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        for result in pool.map(lambda v: run_cycle(v, pin, expected), variants):
            results.append(result)
            write_json(LOGS / 'runtime-matrix.json', results)
    if all(result['passed'] for result in results) and 'paper-26.2' in variants:
        results.append(english_extra(pin, expected))
    write_json(LOGS / 'all-results.json', results)
    write_json(LOGS / 'closed-ports.json', {'checked_at_utc': stamp(), 'closed': ports_closed()})
    assert all(result['passed'] for result in results), 'Some regression cases failed; inspect preserved evidence'
    print('ALL 1.1.0 REQUESTED CYCLES PASSED', flush=True)
