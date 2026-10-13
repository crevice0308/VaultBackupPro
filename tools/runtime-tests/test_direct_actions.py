"""Final-hash-pinned 1.2.0 console regression on six disposable official servers.
No real player, GUI, production data, webhooks, or user servers are involved.
"""
from direct_harness import *
import sys
import traceback

CATALOGS={}
ID_PATTERN=r'(\d{8}T\d{9}Z-[0-9a-f]{12})'

def load_messages(pin):
    with zipfile.ZipFile(pin) as jar:
        for lang in ('ja_JP','en_US'):
            CATALOGS[lang]=dict(line.split('=',1) for line in jar.read('messages/'+lang+'.properties').decode().splitlines()
                                if line and not line.startswith('#') and '=' in line)

def pattern(key,lang='ja_JP'):
    value=CATALOGS[lang][key].split('\\n')[0]
    return ''.join('.+?' if re.fullmatch(r'\{\d+\}',part) else re.escape(part)
                   for part in re.split(r'(\{\d+\})',value))

def expect_key(server,key,lang='ja_JP',start=0,timeout=120):
    return server.expect(pattern(key,lang),timeout=timeout,start=start)

def command_key(server,command,key,lang='ja_JP',timeout=120):
    return expect_key(server,key,lang,server.cmd(command),timeout)

def settled(server,lang='ja_JP',timeout=90):
    until=time.monotonic()+timeout
    acceptable=[CATALOGS[lang][key] for key in ('operation.idle','restore.state.WAITING_FOR_MANUAL_STOP','restore.state.READY')]
    while time.monotonic()<until:
        line=command_key(server,'backup status','status.summary',lang,20)
        if any(value in ANSI.sub('',line) for value in acceptable): return line
        server.stay_alive(.25)
    raise TimeoutError('Plugin did not settle')

def archives(variant): return ROOT/(variant+'-backups')
def exists(variant,backup_id): return all((archives(variant)/(backup_id+s)).is_file() for s in ('.zip','.properties'))

def catalogue(server,expected,lang='ja_JP'):
    settled(server,lang)
    line=command_key(server,'backup status','status.summary',lang)
    count=re.search(r'バックアップ (\d+) 件' if lang=='ja_JP' else r'/ (\d+) backups /',ANSI.sub('',line))
    assert count and int(count.group(1))==expected,line
    command_key(server,'backup list','list.empty' if expected==0 else 'list.title',lang)
    settled(server,lang)
    return ANSI.sub('',line).strip()

def create(server,label,lang='ja_JP',retention_removed=False,probe_busy=False):
    settled(server,lang)
    start=server.cmd('backup create '+label)
    accepted=expect_key(server,'backup.starting',lang,start,timeout=20)
    saved=expect_key(server,'backup.saved',lang,start,timeout=120)
    assert server.lines.index(accepted,start)<server.lines.index(saved,start),'Acceptance must precede save-complete notice'
    if probe_busy:
        command_key(server,'backup list','list.cached',lang)
        command_key(server,'backup status','status.summary',lang)
    expected='backup.retentionremoved' if retention_removed else 'backup.created'
    line=server.expect('(?:'+pattern(expected,lang)+')|(?:'+pattern('job.failed',lang)+')',timeout=300,start=start)
    assert not re.search(pattern('job.failed',lang),ANSI.sub('',line)),line
    assert server.lines.index(saved,start)<server.lines.index(line,start),'Backup result must follow world saving'
    backup_id=re.search(ID_PATTERN,line).group(1)
    if retention_removed: expect_key(server,'backup.retentionempty',lang,start)
    settled(server,lang)
    return backup_id

def reserve(server,variant,backup_id,lang='ja_JP'):
    command_key(server,'backup restore '+backup_id,'restore.reserved',lang,240)
    job,active=active_job(variant)
    assert active['backupId']==backup_id
    wait_status(job,{'WAITING_FOR_MANUAL_STOP'},timeout=30)
    settled(server,lang)
    return job

def cancel(server,variant,job,lang='ja_JP'):
    command_key(server,'backup restore cancel','restore.cancelled',lang,60)
    wait_status(job,{'CANCELLED'},timeout=30)
    settled(server,lang)
    assert not (job/'receipt.properties').exists()

def world_time(server):
    start=server.cmd('execute in minecraft:overworld run time query gametime')
    line=server.expect(r'The (?:game )?time is \d+',timeout=20,start=start)
    return int(re.search(r'The (?:game )?time is (\d+)',ANSI.sub('',line)).group(1))

def helper_exited(job,timeout=30):
    pid=int(props(job/'helper-process.properties')['pid'])
    until=time.monotonic()+timeout
    while time.monotonic()<until:
        stat=Path('/proc')/str(pid)/'stat'
        if not stat.exists(): return {'pid':pid,'observed':'absent'}
        try:
            state=stat.read_text().rsplit(') ',1)[1].split()[0]
            if state=='Z': return {'pid':pid,'observed':'exited_zombie'}
            command=(Path('/proc')/str(pid)/'cmdline').read_bytes().replace(b'\0',b' ').decode()
            assert str(job/'job.properties') in command,'Helper PID identity changed'
        except FileNotFoundError: return {'pid':pid,'observed':'absent'}
        time.sleep(.1)
    raise TimeoutError('Helper did not exit after terminal status')

def cleanup(server,variant,lang='ja_JP'):
    if not server or server.closed or server.p.poll() is not None: return 'already stopped'
    if (ROOT/(variant+'-state')/'active-restore.properties').exists():
        job,active=active_job(variant)
        status=props(job/'status.properties').get('status')
        if status in {'COMMITTED','READY','WAITING_FOR_MANUAL_STOP','CANCEL_PENDING'}: cancel(server,variant,job,lang)
    return server.finish()

def run_cycle(variant,pin,expected_hash):
    result={'variant':variant,'tested_jar_sha256':expected_hash,'started_at_utc':stamp(),'checks':[]}
    server=None; lang='ja_JP'
    try:
        marker=prepare_fixture(variant,pin)
        directory=ROOT/variant
        result['server_jar_sha256']=sha256(directory/'server.jar')
        result['fixture_only_exclusions']=['plugins/spark/tmp/**']
        # Exercise JavaPlugin.saveDefaultConfig itself before applying test paths.
        config_path=directory/'plugins/VaultBackupPro/config.yml'
        intended_config=config_path.read_text()
        config_path.unlink()
        server=Server(variant,'fresh-plugin-generated-default')
        server.expect(r'Done \(',timeout=240)
        expect_key(server,'startup.enabled')
        with zipfile.ZipFile(pin) as archive: shipped_config=archive.read('config.yml')
        generated_config=config_path.read_bytes()
        assert generated_config==shipped_config,'Plugin-generated config differs from frozen shipped default'
        assert b"    - '.paper/console_history'" in generated_config
        result['plugin_generated_default_sha256']=hashlib.sha256(generated_config).hexdigest()
        result['default_config_generation_stop_exit']=server.finish();server=None
        config_path.write_text(intended_config)
        result['checks']+=['fresh_plugin_generated_default_matches_frozen_resource','shipped_default_narrow_console_history_exclusion']
        server=Server(variant,'direct-actions-japanese')
        server.expect(r'Done \(',timeout=240)
        settled(server)
        assert any('VaultBackup Pro 1.2.0' in line for line in server.lines),'1.2.0 startup not observed'
        start=server.cmd('backup help'); expect_key(server,'command.help.retention',start=start)
        segment=ANSI.sub('',''.join(server.lines[start:]))
        assert 'confirm' not in segment.lower(),segment
        catalogue(server,0)
        result['checks']+=['frozen_1_2_0_enabled','japanese_default_help_status_zero_list','help_has_no_confirm_commands']
        first=create(server,'direct-first',probe_busy=True)
        command_key(server,'backup delete '+first+' confirm','command.usage')
        assert exists(variant,first)
        command_key(server,'backup restore '+first+' confirm','command.usage')
        assert not (ROOT/(variant+'-state')/'active-restore.properties').exists()
        second=create(server,'direct-second')
        catalogue(server,2)
        command_key(server,'backup delete '+first,'delete.done',timeout=180)
        catalogue(server,1); assert not exists(variant,first)
        command_key(server,'backup delete '+second,'delete.done',timeout=180)
        catalogue(server,0); assert not exists(variant,second)
        result['checks']+=['immediate_create_acceptance_precedes_save_complete_and_final_result','busy_cached_list_and_status_remain_available','legacy_delete_confirm_rejected_without_deletion',
                          'legacy_restore_confirm_rejected_without_reservation','direct_delete_two_to_one','direct_delete_last_one_to_zero']
        command_key(server,'backup retention bytes 1','settings.updated')
        removed=create(server,'retention-zero',retention_removed=True)
        catalogue(server,0); assert not exists(variant,removed)
        command_key(server,'backup retention bytes 0','settings.updated')
        restored=create(server,'recreate-after-zero')
        catalogue(server,1)
        result['archive']=zipcheck(archives(variant)/(restored+'.zip'),marker,expected_hash)
        result['checks']+=['tiny_byte_retention_removes_last_backup_to_zero','retention_reports_no_remaining_recovery_points',
                          'create_after_zero_succeeds','archive_crc_manifest_sha256_world_libraries_and_plugin_verified','console_history_excluded']
        if variant.startswith(('paper-', 'purpur-')): result['checks'].append('neighbour_paper_version_history_retained')
        mutation=('Changed after 1.2.0 backup: '+variant+'\n').encode()
        (directory/'vaultbackup-fixture.txt').write_bytes(mutation)
        job=reserve(server,variant,restored)
        command_key(server,'backup restore '+restored,'restore.pending')
        command_key(server,'backup delete '+restored,'delete.protected')
        command_key(server,'backup create blocked-reservation','backup.blocked')
        command_key(server,'backup help','command.help.retention')
        command_key(server,'backup status','status.summary')
        command_key(server,'backup list','list.title'); settled(server)
        assert exists(variant,restored)
        cancel(server,variant,job)
        assert (directory/'vaultbackup-fixture.txt').read_bytes()==mutation
        server.stay_alive(2)
        result['checks']+=['single_restore_command_reserves','duplicate_restore_refused','reserved_archive_delete_refused',
                          'creation_while_reserved_refused','help_status_list_available_while_reserved','cancel_keeps_server_running_and_files_unchanged']
        job=reserve(server,variant,restored)
        result['job_id']=job.name
        before=world_time(server); observation=server.stay_alive(5); after=world_time(server)
        assert after>before
        job_properties=props(job/'job.properties')
        assert not (job/'normal-stop.properties').exists()
        assert not (job/'receipt.properties').exists()
        assert not Path(job_properties['stage']).exists()
        assert not Path(job_properties['rollback']).exists()
        assert (directory/'vaultbackup-fixture.txt').read_bytes()==mutation
        result['alive_observation_seconds']=observation
        result['world_ticks_delta']=after-before
        result['checks']+=['rereservation_after_cancel','world_and_jvm_still_running_after_reservation',
                          'no_stage_swap_or_success_before_manual_stop']
        result['server_pid']=server.p.pid
        result['manual_stop_issued_at_utc']=stamp()
        result['restore_shutdown_exit']=server.finish()
        result['original_process_exited_at_utc']=stamp()
        assert server.p.poll()==0
        server=None
        wait_status(job,{'SUCCESS'},timeout=300)
        result['helper_success_observed_at_utc']=stamp()
        result['helper_exit']=helper_exited(job)
        assert (job/'normal-stop.properties').is_file() and (job/'receipt.properties').is_file()
        assert (directory/'vaultbackup-fixture.txt').read_bytes()==marker
        assert (Path(job_properties['rollback'])/'vaultbackup-fixture.txt').read_bytes()==mutation
        port=int(props(directory/'server.properties')['server-port'])
        with socket.socket() as sock:
            sock.settimeout(.5); assert sock.connect_ex(('127.0.0.1',port))!=0
        result['checks']+=['manual_stop_actual_jvm_exit_then_offline_restore','mutation_preserved_in_rollback','no_automatic_restart']
        server=Server(variant,'manual-restart-receipt')
        receipt=expect_key(server,'startup.success',timeout=240)
        server.expect(r'Done \(',timeout=240)
        result['japanese_receipt']=ANSI.sub('',receipt).strip()
        assert foreground_before(''.join(server.lines),'復元成功を検証しました') in {32,92}
        assert (job/'receipt-consumed.properties').is_file()
        assert sha256(directory/'plugins/VaultBackupPro-1.2.0.jar')==expected_hash
        catalogue(server,1)
        result['checks']+=['manual_restart_green_japanese_verified_success','receipt_consumed','restored_final_jar_hash_unchanged']
        result['final_stop_exit']=server.finish(); server=None; result['passed']=True
    except Exception as exc:
        result['passed']=False; result['error']=str(exc); traceback.print_exc()
        try: result['failure_cleanup']=cleanup(server,variant,lang)
        except Exception as cleanup_error: result['failure_cleanup_error']=str(cleanup_error)
    result['completed_at_utc']=stamp()
    write_json(LOGS/(variant+'-result.json'),result)
    print('CYCLE RESULT '+json.dumps(result,ensure_ascii=False),flush=True)
    return result

if __name__=='__main__':
    assert len(sys.argv)>=2,'Provide parent-frozen final JAR SHA-256'
    expected_hash=sys.argv[1]
    pin,inspection,config=pin_artifact(expected_hash)
    load_messages(pin)
    results=[]
    for variant in sys.argv[2:] or VARIANTS:
        if (LOGS/'stop-after-current.request').exists():
            print('Runner stopped before another target by explicit request',flush=True);break
        assert variant in VARIANTS
        results.append(run_cycle(variant,pin,expected_hash))
    write_json(LOGS/('runtime-results-'+'-'.join(sys.argv[2:] or ['all'])+'.json'),{'tested_jar_sha256':expected_hash,'results':results,'ports_closed':ports_closed(),
                'untested':['actual_player_GUI_clicks','permission_revocation_mid_verification','Windows','real_webhook','production_scale','power_loss']})
    sys.exit(0 if all(r['passed'] for r in results) else 1)
