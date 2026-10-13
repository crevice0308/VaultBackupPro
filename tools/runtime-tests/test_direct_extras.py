"""English direct-action restore cycle and explicit SIGKILL-without-stop refusal.
Only freshly created disposable fixture files/processes are changed.
"""
from test_direct_actions import *


def english_cycle(pin,expected_hash):
    variant='paper-26.3'; lang='en_US'; directory=ROOT/variant
    result={'variant':variant,'scenario':'english_direct_actions','tested_jar_sha256':expected_hash,'started_at_utc':stamp(),'checks':[]}
    server=None
    if not directory.exists(): prepare_fixture(variant,pin)
    set_language(variant,'language',lang)
    try:
        server=Server(variant,'english-direct-actions')
        server.expect(r'Done \(',timeout=240); settled(server,lang)
        command_key(server,'backup help','command.help.retention',lang)
        command_key(server,'backup retention bytes 1','settings.updated',lang)
        removed=create(server,'english-retention-zero',lang,retention_removed=True)
        catalogue(server,0,lang); assert not exists(variant,removed)
        command_key(server,'backup retention bytes 0','settings.updated',lang)
        backup_id=create(server,'english-recreated',lang)
        catalogue(server,1,lang)
        original=(directory/'vaultbackup-fixture.txt').read_bytes()
        mutation=b'English direct restore mutation\n'; (directory/'vaultbackup-fixture.txt').write_bytes(mutation)
        job=reserve(server,variant,backup_id,lang)
        command_key(server,'backup auto on','settings.updated',lang)
        command_key(server,'backup interval 1','settings.updated',lang)
        config_path=directory/'plugins/VaultBackupPro/config.yml'
        set_language(variant,'language','ja_JP')
        command_key(server,'backup reload','settings.reloaded','ja_JP')
        command_key(server,'backup status','status.summary','ja_JP')
        assert props(job/'job.properties')['language']=='en','Committed helper language must remain immutable'
        set_language(variant,'language',lang)
        command_key(server,'backup reload','settings.reloaded',lang)
        safe_config=config_path.read_text()
        config_path.write_text(re.sub(r'(?m)^  backup-directory:.*$', '  backup-directory: ../'+variant+'-rejected-storage',safe_config))
        rejected=command_key(server,'backup reload','settings.rejected',lang)
        assert CATALOGS[lang]['settings.restart'] in ANSI.sub('',rejected)
        config_path.write_text(safe_config)
        config_path.write_text(re.sub(r'(?m)^  compression-level:.*$', '  compression-level: 5',safe_config))
        rejected=command_key(server,'backup reload','settings.rejected',lang)
        assert CATALOGS[lang]['settings.reserved'] in ANSI.sub('',rejected)
        config_path.write_text(safe_config)
        command_key(server,'backup reload','settings.reloaded',lang)
        command_key(server,'backup retention bytes 1','settings.rejected',lang)
        assert exists(variant,backup_id) and props(job/'status.properties')['status']=='WAITING_FOR_MANUAL_STOP'
        cancel(server,variant,job,lang)
        command_key(server,'backup retention bytes 1','settings.updated',lang)
        server.drain();schedule_start=len(server.lines)
        scheduled=expect_key(server,'backup.retentionremoved',lang,start=schedule_start,timeout=110)
        expect_key(server,'backup.retentionempty',lang,start=schedule_start,timeout=15)
        accepted=expect_key(server,'backup.starting',lang,start=schedule_start,timeout=10)
        saved=expect_key(server,'backup.saved',lang,start=schedule_start,timeout=10)
        assert server.lines.index(accepted,schedule_start)<server.lines.index(saved,schedule_start)<server.lines.index(scheduled,schedule_start)
        scheduled_id=re.search(ID_PATTERN,scheduled).group(1)
        assert scheduled_id!=backup_id
        assert not exists(variant,scheduled_id) and not exists(variant,backup_id)
        settled(server,lang);catalogue(server,0,lang)
        command_key(server,'backup auto off','settings.updated',lang)
        command_key(server,'backup retention bytes 0','settings.updated',lang)
        result['automatic_retention_removed_id']=scheduled_id
        result['scheduled_no_manual_create_in_observation']=True
        result['checks']+=['reserved_auto_on_interval_change_allowed','reserved_language_reload_roundtrip_allowed',
                          'committed_helper_language_unchanged','reserved_storage_change_rejected','reserved_integrity_change_rejected',
                          'reserved_retention_change_rejected','real_one_minute_scheduled_backup_after_cancel','scheduled_acceptance_save_result_message_order','automatic_retention_to_zero_truthful']
        (directory/'vaultbackup-fixture.txt').write_bytes(original)
        backup_id=create(server,'english-after-scheduled-zero',lang)
        (directory/'vaultbackup-fixture.txt').write_bytes(mutation)
        job=reserve(server,variant,backup_id,lang)
        server.stay_alive(2)
        result['stop_exit']=server.finish(); server=None
        wait_status(job,{'SUCCESS'},timeout=300); result['helper_exit']=helper_exited(job)
        assert (directory/'vaultbackup-fixture.txt').read_bytes()==original
        server=Server(variant,'english-manual-restart')
        receipt=expect_key(server,'startup.success',lang,timeout=240)
        server.expect(r'Done \(',timeout=240)
        assert foreground_before(''.join(server.lines),'RESTORE VERIFIED SUCCESS') in {32,92}
        result['receipt']=ANSI.sub('',receipt).strip()
        catalogue(server,1,lang)
        command_key(server,'backup delete '+backup_id,'delete.done',lang)
        catalogue(server,0,lang)
        result['checks']+=['english_help','english_retention_zero_truthful','english_recreate','english_direct_reserve_cancel_rereserve',
                          'english_restore_after_manual_stop','english_green_verified_receipt','english_direct_last_delete_zero']
        result['final_stop_exit']=server.finish(); server=None; result['passed']=True
    except Exception as exc:
        result['passed']=False; result['error']=str(exc); traceback.print_exc()
        try: result['failure_cleanup']=cleanup(server,variant,lang)
        except Exception as e: result['failure_cleanup_error']=str(e)
    result['completed_at_utc']=stamp(); write_json(LOGS/'english-direct-result.json',result)
    print('ENGLISH RESULT '+json.dumps(result,ensure_ascii=False),flush=True)
    return result


def crash_refusal(pin,expected_hash):
    variant='paper-26.2-crash'; directory=ROOT/variant
    result={'variant':variant,'scenario':'SIGKILL_without_normal_stop','tested_jar_sha256':expected_hash,'started_at_utc':stamp(),'checks':[]}
    server=None
    try:
        assert not directory.exists()
        prepare_fixture(variant,pin,baseline_variant='paper-26.2')
        properties=directory/'server.properties'
        properties.write_text(re.sub(r'(?m)^server-port=.*$','server-port=25587',properties.read_text()))
        marker=b'Fresh disposable crash fixture\n'; (directory/'vaultbackup-fixture.txt').write_bytes(marker)
        server=Server(variant,'reserve-force-kill')
        server.expect(r'Done \(',timeout=240); settled(server)
        backup_id=create(server,'crash-refusal')
        mutation=b'SIGKILL must not restore this mutation\n'; (directory/'vaultbackup-fixture.txt').write_bytes(mutation)
        job=reserve(server,variant,backup_id)
        assert not (job/'normal-stop.properties').exists()
        server.log.write(f'TEST FAULT [{stamp()}]: SIGKILL disposable server PID {server.p.pid}; no console stop\n'); server.log.flush()
        server.p.kill(); server.p.wait(timeout=30); server.reader.join(timeout=5); server.drain(); server.log.close(); server.closed=True
        process_info=json.loads(server.process_record.read_text());process_info.update({'exited_at_utc':stamp(),'returncode':server.p.returncode,'stop_method':'explicit disposable SIGKILL fault injection'});write_json(server.process_record,process_info)
        result['forced_exit_code']=server.p.returncode; assert server.p.returncode==-9; server=None
        wait_status(job,{'FAILED_BEFORE_SWAP'},timeout=90); result['helper_exit']=helper_exited(job)
        assert not (job/'normal-stop.properties').exists() and not (job/'receipt.properties').exists()
        job_properties=props(job/'job.properties')
        assert not Path(job_properties['stage']).exists() and not Path(job_properties['rollback']).exists()
        assert (directory/'vaultbackup-fixture.txt').read_bytes()==mutation
        server=Server(variant,'restart-after-crash-refusal')
        failure=server.expect('前回の復元は成功していません',timeout=240)
        server.expect(r'Done \(',timeout=240)
        assert foreground_before(''.join(server.lines),'前回の復元は成功していません') in {31,91}
        assert not any(re.search(pattern('startup.success'),ANSI.sub('',line)) for line in server.lines)
        assert (directory/'vaultbackup-fixture.txt').read_bytes()==mutation
        result['failure_notice']=ANSI.sub('',failure).strip()
        result['checks']=['SIGKILL_without_normal_stop','helper_refused_before_swap','no_stage_rollback_or_success_receipt',
                          'mutation_untouched','restart_red_failure_notice_without_false_success']
        result['final_stop_exit']=server.finish(); server=None; result['passed']=True
    except Exception as exc:
        result['passed']=False; result['error']=str(exc); traceback.print_exc()
        try: result['failure_cleanup']=cleanup(server,variant)
        except Exception as e: result['failure_cleanup_error']=str(e)
    result['completed_at_utc']=stamp(); write_json(LOGS/'crash-refusal-result.json',result)
    print('CRASH RESULT '+json.dumps(result,ensure_ascii=False),flush=True)
    return result

if __name__=='__main__':
    assert len(sys.argv)==2,'Provide frozen plugin SHA-256'
    pin,inspection,config=pin_artifact(sys.argv[1]); load_messages(pin)
    results=[english_cycle(pin,sys.argv[1]),crash_refusal(pin,sys.argv[1])]
    write_json(LOGS/'extra-results.json',{'tested_jar_sha256':sys.argv[1],'results':results,'ports_closed':ports_closed([25582,25587])})
    sys.exit(0 if all(r['passed'] for r in results) else 1)
