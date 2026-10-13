"""Execute only after final artifact SHA and localized messages are stable.
Usage: python test_manualstop_revision.py <sha256> [variant ...]
"""
from manualstop_harness import *
import sys
import traceback

# Populated/verified against the final message catalog before executing servers.
PATTERNS_FILE = Path(__file__).with_name('manualstop-message-patterns.json')
P = json.loads(PATTERNS_FILE.read_text()) if PATTERNS_FILE.exists() else {}


def idle(server, language='ja_JP', timeout=90):
    patterns = P[language]
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        start = server.cmd('backup status')
        line = server.expect(patterns['status'], timeout=15, start=start)
        if re.search(patterns['idle'], ANSI.sub('', line)):
            return line
        time.sleep(.4)
    raise TimeoutError('Plugin did not become idle')


def world_time(server):
    start = server.cmd('execute in minecraft:overworld run time query gametime')
    line = server.expect(r'The (?:game )?time is \d+', timeout=20, start=start)
    return int(re.search(r'The (?:game )?time is (\d+)', ANSI.sub('', line)).group(1))


def preview_and_confirm(server, variant, backup_id, marker, dwell=5, language="ja_JP"):
    patterns = P[language]
    start = server.cmd('backup restore ' + backup_id)
    server.expect(patterns['preview'], timeout=180, start=start)
    idle(server, language)
    start = server.cmd('backup restore ' + backup_id + ' confirm')
    ready_line = server.expect(patterns['armed'], timeout=180, start=start)
    job, active = active_job(variant)
    assert active['backupId'] == backup_id
    wait_status(job, {'WAITING_FOR_MANUAL_STOP'}, timeout=20)
    first = world_time(server)
    elapsed = server.stay_alive(dwell)
    second = world_time(server)
    assert second > first, ('World did not tick while awaiting manual stop', first, second)
    start = server.cmd('backup status')
    status_line = server.expect(patterns['armed_status'], timeout=20, start=start)
    start = server.cmd('backup delete ' + backup_id)
    server.expect(patterns['delete_protected'], timeout=20, start=start)
    start = server.cmd('backup create forbidden-while-reserved')
    server.expect(patterns['backup_blocked'], timeout=20, start=start)
    server.drain()
    assert server.p.poll() is None
    assert props(job / 'status.properties')['status'] == 'WAITING_FOR_MANUAL_STOP'
    assert not (job / 'receipt.properties').exists()
    job_properties = props(job / 'job.properties')
    assert job_properties['formatVersion'] == '2'
    assert job_properties['shutdownPolicy'] == 'MANUAL_NORMAL_STOP'
    assert 'automaticDeadline' not in job_properties
    assert not (job / 'normal-stop.properties').exists(), 'Normal-stop authorization appeared before manual stop'
    assert not Path(job_properties['stage']).exists(), 'Helper extracted before JVM exit'
    assert not Path(job_properties['rollback']).exists(), 'Helper renamed root before JVM exit'
    assert (ROOT / variant / 'vaultbackup-fixture.txt').read_bytes() == marker
    return job, {
        'job_id': job.name, 'parent_pid': server.p.pid, 'language': language,
        'observed_alive_before_manual_stop_seconds': elapsed,
        'world_gametime_before': first, 'world_gametime_after': second,
        'reservation_message': ANSI.sub('', ready_line).strip(),
        'reservation_status': ANSI.sub('', status_line).strip(),
        'helper_ready_without_stage_rollback_or_receipt': True,
        'marker_unchanged_before_manual_stop': True,
        'reserved_archive_delete_preview_refused': True,
        'new_backup_creation_while_reserved_refused': True,
    }


def cancel_confirmed(server, variant, job, marker, language='ja_JP'):
    start = server.cmd('backup restore cancel')
    line = server.expect(P[language]['cancel'], timeout=40, start=start)
    wait_status(job, {'CANCELLED'}, timeout=40)
    idle(server, language)
    assert server.p.poll() is None
    assert (ROOT / variant / 'vaultbackup-fixture.txt').read_bytes() == marker
    assert not (job / 'receipt.properties').exists()
    assert not Path(props(job / 'job.properties')['rollback']).exists()
    return ANSI.sub('', line).strip()


def safe_failure_cleanup(server, variant):
    """Disarm only this fixture's current job before any cleanup stop."""
    if server is None or server.closed or server.p.poll() is not None:
        return 'already_stopped'
    # A console command created before worlds exist can carry an invalid vanilla
    # command source; never dispatch fixture cleanup stop until startup is ready.
    if not any(re.search(r'Done \(', line) for line in server.lines):
        server.expect(r'Done \(', timeout=180)
    active = ROOT / (variant + '-state') / 'active-restore.properties'
    if active.exists():
        job, unused = active_job(variant)
        status = props(job / 'status.properties').get('status')
        if status in {'COMMITTED', 'READY', 'WAITING_FOR_MANUAL_STOP', 'CANCEL_PENDING'}:
            start = server.cmd('backup restore cancel')
            server.expect(P['ja_JP']['cancel'], timeout=40, start=start)
            wait_status(job, {'CANCELLED'}, timeout=40)
    return server.finish()


def run_cycle(variant, pin, expected_hash):
    result = {'variant': variant, 'tested_jar_sha256': expected_hash,
              'started_at_utc': stamp(), 'checks': [],
              'console_color_mode': 'jansi.passthrough=true' if variant.startswith('spigot-') else 'default'}
    server = None
    try:
        baseline = prepare_fixture(variant, pin, P['language_key'])
        directory = ROOT / variant
        result['server_jar_sha256'] = sha256(directory / 'server.jar')
        backups = ROOT / (variant + '-backups')
        server = Server(variant, 'manualstop-backup-restore')
        server.expect(r'Done \(', timeout=180)
        idle(server)
        assert any('VaultBackup Pro 1.0.0' in line for line in server.lines), 'Plugin enable missing'
        result['checks'].append('final_non_obfuscated_plugin_enabled')
        for command, pattern in [('backup help', 'help'), ('backup status', 'status'), ('backup list', 'list')]:
            start = server.cmd(command)
            line = server.expect(P['ja_JP'][pattern], start=start)
            assert re.search('[\u3040-\u30ff\u4e00-\u9fff]', line), ('Expected Japanese', line)
            result['checks'].append('japanese_' + pattern)
        idle(server)
        start = server.cmd('backup create manualstop-runtime')
        line = server.expect(P['ja_JP']['created'], timeout=240, start=start)
        backup_id = re.search(r'(\d{8}T\d{9}Z-[0-9a-f]{12})', line).group(1)
        result['backup_id'] = backup_id
        result['archive'] = zipcheck(backups / (backup_id + '.zip'), baseline, expected_hash)
        result['checks'].append('independent_zip_crc_and_full_manifest_sha256_verification')
        idle(server)
        mutated = f'MUTATED AFTER BACKUP manual-stop {variant}\n'.encode()
        (directory / 'vaultbackup-fixture.txt').write_bytes(mutated)
        if variant == 'paper-26.2':
            # Two distinct confirmed cancellations prove retry works in the same
            # JVM; real stop/restart proves cancellation remains durable offline.
            cancelled = []
            for number in (1, 2):
                job, details = preview_and_confirm(server, variant, backup_id, mutated)
                details['japanese_cancel_message'] = cancel_confirmed(server, variant, job, mutated)
                cancelled.append(details)
            result['confirmed_cancellations'] = cancelled
            result['checks'] += ['confirmed_restore_cancelled_twice', 'same_jvm_rereservation_after_confirmed_cancellation']
            result['cancelled_stop_exit'] = server.finish()
            server = None
            time.sleep(5)
            for details in cancelled:
                job = ROOT / (variant + '-state') / 'restore-jobs' / details['job_id']
                assert props(job / 'status.properties')['status'] == 'CANCELLED'
                assert not (job / 'receipt.properties').exists()
                assert not Path(props(job / 'job.properties')['rollback']).exists()
            assert (directory / 'vaultbackup-fixture.txt').read_bytes() == mutated
            old_job = ROOT / (variant + '-state') / 'restore-jobs' / cancelled[0]['job_id']
            helper = ROOT / (variant + '-state') / 'helper/vaultbackup-restore.jar'
            replay = subprocess.run([JAVA, '-jar', str(helper), '--job', str(old_job / 'job.properties'),
                                     '--manual', '--i-confirm-server-stopped'],
                                    cwd=ROOT / (variant + '-state'), capture_output=True, text=True,
                                    encoding='utf-8', errors='replace', timeout=45)
            (LOGS / (variant + '-cancelled-helper-manual-retry.log')).write_text(replay.stdout + replay.stderr)
            assert replay.returncode == 3, ('Cancelled helper was not refused', replay.returncode)
            assert props(old_job / 'status.properties')['status'] == 'CANCELLED'
            assert not (old_job / 'receipt.properties').exists()
            assert not Path(props(old_job / 'job.properties')['rollback']).exists()
            assert (directory / 'vaultbackup-fixture.txt').read_bytes() == mutated
            result['cancelled_helper_retry_exit'] = replay.returncode
            result['checks'].append('cancelled_old_helper_manual_retry_refused')
            result['checks'].append('confirmed_cancellation_then_manual_stop_no_restore_marker_preserved')
            server = Server(variant, 'cancelled-restart-rereserve')
            server.expect(r'Done \(', timeout=180)
            idle(server)
            assert not any(re.search(P['ja_JP']['receipt'], ANSI.sub('', line)) for line in server.lines)
            assert (directory / 'vaultbackup-fixture.txt').read_bytes() == mutated
            result['checks'].append('cancelled_restart_no_false_success_receipt')
        job, details = preview_and_confirm(server, variant, backup_id, mutated)
        result['manual_stop_observation'] = details
        result['job_id'] = job.name
        result['checks'] += ['confirmed_restore_keeps_jvm_alive', 'world_responds_and_ticks_while_armed',
                             'japanese_waiting_for_manual_stop_status', 'no_restore_before_parent_exit']
        result['manual_stop_requested_at_utc'] = stamp()
        result['restore_shutdown_exit'] = server.finish()
        server = None
        assert wait_status(job, {'SUCCESS'}) == 'SUCCESS'
        assert (job / 'normal-stop.properties').is_file(), 'Normal stop authorization missing'
        result['offline_restore_success_at_utc'] = stamp()
        time.sleep(2)
        port = int(props(directory / 'server.properties')['server-port'])
        with socket.socket() as sock:
            sock.settimeout(.5)
            assert sock.connect_ex(('127.0.0.1', port)) != 0, 'Server automatically restarted after restore'
        result['checks'].append('server_remained_stopped_after_helper_success')
        assert (directory / 'vaultbackup-fixture.txt').read_bytes() == baseline
        assert (job / 'receipt.properties').exists()
        rollback = Path(props(job / 'job.properties')['rollback'])
        assert rollback.is_dir()
        assert (rollback / 'vaultbackup-fixture.txt').read_bytes() == mutated
        result['rollback'] = str(rollback)
        result['checks'] += ['helper_restored_after_actual_manual_jvm_exit', 'marker_restored',
                             'rollback_preserved_mutated_original', 'authenticated_offline_success_receipt_created']
        server = Server(variant, 'manual-restart-japanese-receipt')
        receipt = server.expect(P['ja_JP']['receipt'], timeout=180)
        server.expect(r'Done \(', timeout=180)
        assert re.search('[\u3040-\u30ff\u4e00-\u9fff]', receipt)
        result['japanese_receipt'] = ANSI.sub('', receipt).strip()
        receipt_index = server.lines.index(receipt)
        banner_prefix = ''.join(server.lines[max(0, receipt_index - 3):receipt_index + 1])
        result['green_receipt_ansi'] = foreground_before(''.join(server.lines), '復元成功を検証しました') in {32, 92}
        assert result['green_receipt_ansi'], repr(banner_prefix)
        idle(server)
        assert (job / 'receipt-consumed.properties').is_file()
        assert sha256(directory / 'plugins/VaultBackupPro-1.0.0.jar') == expected_hash
        assert (directory / 'vaultbackup-fixture.txt').read_bytes() == baseline
        start = server.cmd('backup list')
        server.expect(re.escape(backup_id), timeout=120, start=start)
        result['checks'] += ['manual_restart_green_japanese_success_receipt', 'receipt_consumed',
                             'restored_final_plugin_hash_verified', 'restored_backup_listed']
        result['final_stop_exit'] = server.finish()
        server = None
        result['passed'] = True
    except Exception as error:
        result['passed'] = False
        result['error'] = str(error)
        traceback.print_exc()
        try:
            result['failure_cleanup'] = safe_failure_cleanup(server, variant)
        except Exception as cleanup_error:
            result['failure_cleanup_error'] = str(cleanup_error)
    result['completed_at_utc'] = stamp()
    write_json(LOGS / f'{variant}-result.json', result)
    print('CYCLE RESULT', json.dumps(result, ensure_ascii=False), flush=True)
    return result


def english_smoke(pin, expected_hash):
    variant = 'paper-26.2'
    result = {'variant': variant, 'language': 'en_US', 'tested_jar_sha256': expected_hash,
              'checks': [], 'started_at_utc': stamp()}
    server = None
    # Long-running Paper tests can start Spark's JFR profiler. Its actively written
    # temporary output is not application data; exclude it only for these extras.
    config_path = ROOT / variant / 'plugins/VaultBackupPro/config.yml'
    config = config_path.read_text()
    if 'plugins/spark/tmp/**' not in config:
        assert '  exclude:\n' in config
        config_path.write_text(config.replace('  exclude:\n', '  exclude:\n  - plugins/spark/tmp/**\n'))
    result['fixture_only_exclusions'] = ['plugins/spark/tmp/**']
    set_language(variant, P['language_key'], 'en_US')
    try:
        server = Server(variant, 'english-smoke')
        server.expect(r'Done \(', timeout=180)
        idle(server, 'en_US')
        result['english_messages'] = []
        for command, pattern in [('backup help', 'help'), ('backup status', 'status'),
                                 ('backup list', 'list'), ('backup interval 0', 'invalid_interval')]:
            start = server.cmd(command)
            line = server.expect(P['en_US'][pattern], start=start)
            clean = ANSI.sub('', line).strip()
            assert not re.search('[\u3040-\u30ff\u4e00-\u9fff]', clean), ('Unexpected Japanese', clean)
            result['english_messages'].append(clean)
            result['checks'].append('english_' + pattern)
        assert sha256(ROOT / variant / 'plugins/VaultBackupPro-1.0.0.jar') == expected_hash
        backups = ROOT / (variant + '-backups')
        marker = (ROOT / variant / 'vaultbackup-fixture.txt').read_bytes()
        backup_id = sorted(backups.glob('*.zip'))[-1].stem
        job, observation = preview_and_confirm(server, variant, backup_id, marker, language='en_US')
        result['english_reservation'] = observation
        result['english_cancel_message'] = cancel_confirmed(server, variant, job, marker, 'en_US')
        start = server.cmd('backup restore ' + backup_id + ' confirm')
        server.expect(P['en_US']['no_confirmation'], timeout=20, start=start)
        result['checks'] += ['english_preview_confirmed_reservation_status_and_cancel',
                             'english_cancelled_confirmation_refused']
        # Fresh final-artifact scheduling and generated-archive retention coverage.
        idle(server, 'en_US')
        start = server.cmd('backup interval 1')
        server.expect(P['en_US']['updated'], start=start)
        before = set(backups.glob('*.zip'))
        began = time.monotonic()
        start = server.cmd('backup auto on')
        server.expect(P['en_US']['updated'], start=start)
        line = server.expect(P['en_US']['created'], timeout=150, start=start)
        elapsed = time.monotonic() - began
        automatic_id = re.search(r'(\d{8}T\d{9}Z-[0-9a-f]{12})', line).group(1)
        assert elapsed >= 59, elapsed
        idle(server, 'en_US')
        start = server.cmd('backup auto off')
        server.expect(P['en_US']['updated'], start=start)
        assert backups / (automatic_id + '.zip') in set(backups.glob('*.zip')) - before
        assert props(backups / (automatic_id + '.properties'))['label'] == 'automatic'
        result['automatic_backup_id'] = automatic_id
        result['automatic_elapsed_seconds'] = elapsed
        result['automatic_archive'] = zipcheck(backups / (automatic_id + '.zip'), marker, expected_hash)
        result['checks'].append('real_one_minute_automatic_backup_on_final_save_adapter')
        idle(server, 'en_US')
        start = server.cmd('backup retention count 2')
        server.expect(P['en_US']['updated'], start=start)
        original = set(backups.glob('*.zip'))
        created = []
        for number in range(3):
            idle(server, 'en_US')
            start = server.cmd('backup create final-retention-' + str(number))
            line = server.expect(P['en_US']['created'], timeout=240, start=start)
            created.append(re.search(r'(\d{8}T\d{9}Z-[0-9a-f]{12})', line).group(1))
            idle(server, 'en_US')
            assert len(list(backups.glob('*.zip'))) == 2
        remaining = set(backups.glob('*.zip'))
        assert len(remaining) == 2
        assert backups / (created[-1] + '.zip') in remaining
        assert len(original - remaining) >= 1
        result['remaining_archives'] = sorted(path.name for path in remaining)
        result['removed_generated_archive_count'] = len(original - remaining)
        result['checks'].append('count_retention_keeps_two_generated_archives_on_final_artifact')
        start = server.cmd('backup retention count 20')
        server.expect(P['en_US']['updated'], start=start)
        result['stop_exit'] = server.finish()
        server = None
        result['passed'] = True
    except Exception as error:
        result['passed'] = False
        result['error'] = str(error)
        traceback.print_exc()
        try:
            result['failure_cleanup'] = safe_failure_cleanup(server, variant)
        except Exception as cleanup_error:
            result['failure_cleanup_error'] = str(cleanup_error)
    finally:
        if server is None or server.p.poll() is not None:
            set_language(variant, P['language_key'], 'ja_JP')
    result['completed_at_utc'] = stamp()
    write_json(LOGS / 'english-smoke-result.json', result)
    return result


if __name__ == '__main__':
    assert len(sys.argv) >= 2, 'Provide the parent-confirmed final artifact SHA-256'
    assert P, 'Verify final localized message patterns before runtime startup'
    expected_hash = sys.argv[1]
    pin, inspection, config = pin_artifact(expected_hash)
    variants = sys.argv[2:] or VARIANTS
    assert set(variants) <= set(VARIANTS), variants
    results = []
    for variant in variants:
        result = run_cycle(variant, pin, expected_hash)
        results.append(result)
        all_results = [json.loads(path.read_text()) for path in sorted(LOGS.glob('*-result.json'))
                       if path.name not in {'english-smoke-result.json', 'crash-refusal-result.json'}]
        assert all(item['tested_jar_sha256'] == expected_hash for item in all_results)
        write_json(LOGS / 'runtime-matrix.json', all_results)
        if not result['passed']:
            sys.exit(1)
    if 'paper-26.2' in variants:
        english = english_smoke(pin, expected_hash)
        if not english['passed']:
            sys.exit(1)
    write_json(LOGS / 'closed-ports.json', {'checked_at_utc': stamp(), 'closed': ports_closed()})
    print('ALL REQUESTED CYCLES PASSED', flush=True)
