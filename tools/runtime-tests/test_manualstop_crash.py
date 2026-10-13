"""A disposable Paper fixture must not restore after SIGKILL without normal stop."""
from test_manualstop_revision import *


def crash_refusal(pin, expected_hash):
    variant = 'paper-26.2-crash'
    directory = ROOT / variant
    result = {'variant': variant, 'scenario': 'force_kill_before_manual_normal_stop',
              'tested_jar_sha256': expected_hash, 'checks': [], 'started_at_utc': stamp()}
    server = None
    try:
        assert not directory.exists(), 'Preserve previous crash fixture and evidence'
        shutil.copytree(ROOT / 'paper-26.2', directory,
                        ignore=shutil.ignore_patterns('logs', '*.log', '.paper-remapped'))
        config_path = directory / 'plugins/VaultBackupPro/config.yml'
        config_path.write_text(config_path.read_text().replace('../paper-26.2-', '../' + variant + '-'))
        property_path = directory / 'server.properties'
        property_path.write_text(re.sub(r'(?m)^server-port=.*$', 'server-port=25587', property_path.read_text()))
        baseline = b'VaultBackup isolated crash-refusal baseline\n'
        (directory / 'vaultbackup-fixture.txt').write_bytes(baseline)
        server = Server(variant, 'prepare-and-force-kill')
        server.expect(r'Done \(', timeout=180)
        idle(server)
        start = server.cmd('backup create crash-refusal')
        line = server.expect(P['ja_JP']['created'], timeout=240, start=start)
        backup_id = re.search(r'(\d{8}T\d{9}Z-[0-9a-f]{12})', line).group(1)
        result['backup_id'] = backup_id
        result['archive'] = zipcheck(ROOT / (variant + '-backups') / (backup_id + '.zip'), baseline, expected_hash)
        idle(server)
        mutated = b'MUTATED: crash must preserve current root, without restore\n'
        (directory / 'vaultbackup-fixture.txt').write_bytes(mutated)
        job, observation = preview_and_confirm(server, variant, backup_id, mutated)
        result['manual_stop_observation'] = observation
        result['job_id'] = job.name
        assert not (job / 'normal-stop.properties').exists()
        server.log.write(f'TEST FAULT [{stamp()}]: SIGKILL disposable server PID {server.p.pid}; no stop command\n')
        server.log.flush()
        server.p.kill()
        server.p.wait(timeout=30)
        server.reader.join(timeout=5)
        server.drain()
        server.log.close()
        server.closed = True
        result['forced_exit_code'] = server.p.returncode
        assert server.p.returncode == -9
        server = None
        assert wait_status(job, {'FAILED_BEFORE_SWAP'}, timeout=60) == 'FAILED_BEFORE_SWAP'
        time.sleep(2)
        assert not (job / 'normal-stop.properties').exists()
        assert not (job / 'receipt.properties').exists()
        job_properties = props(job / 'job.properties')
        assert not Path(job_properties['stage']).exists()
        assert not Path(job_properties['rollback']).exists()
        assert (directory / 'vaultbackup-fixture.txt').read_bytes() == mutated
        result['helper_failure_message'] = props(job / 'status.properties').get('message')
        result['checks'] += ['force_killed_without_normal_stop_authorization', 'helper_failed_before_swap',
                             'no_staging_no_rollback_no_success_receipt', 'mutated_marker_preserved_after_crash']
        server = Server(variant, 'restart-after-refusal')
        failure = server.expect('前回の復元は成功していません', timeout=180)
        server.expect(r'Done \(', timeout=180)
        result['japanese_failure_message'] = ANSI.sub('', failure).strip()
        failure_index = server.lines.index(failure)
        failure_prefix = ''.join(server.lines[max(0, failure_index - 3):failure_index + 1])
        result['red_failure_ansi'] = foreground_before(''.join(server.lines), '前回の復元は成功していません') in {31, 91}
        assert result['red_failure_ansi'], repr(failure_prefix)
        server.expect(r'Done \(', timeout=180)
        idle(server)
        assert not any(re.search(P['ja_JP']['receipt'], ANSI.sub('', line)) for line in server.lines)
        assert (directory / 'vaultbackup-fixture.txt').read_bytes() == mutated
        result['checks'].append('restart_red_japanese_failure_without_false_success')
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
    write_json(LOGS / 'crash-refusal-result.json', result)
    print('CRASH REFUSAL RESULT', json.dumps(result, ensure_ascii=False), flush=True)
    return result


if __name__ == '__main__':
    assert len(sys.argv) == 2, 'Provide final artifact SHA-256'
    pin, inspection, config = pin_artifact(sys.argv[1])
    result = crash_refusal(pin, sys.argv[1])
    sys.exit(0 if result['passed'] else 1)
