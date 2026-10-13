"""Observe supported-server hard-conflict and stale-waiting startup boundaries.
The hard-conflict probe reports world changes honestly; a plugin shutdown request
is not assumed to prevent pre-plugin or in-progress server startup writes.
"""
from test_direct_actions import *
import signal


def named_fixture(variant,pin,port):
    marker=prepare_fixture(variant,pin,baseline_variant='paper-26.3')
    properties=ROOT/variant/'server.properties'
    properties.write_text(re.sub(r'(?m)^server-port=.*$',f'server-port={port}',properties.read_text()))
    return marker


def world_hashes(directory):
    return {str(path.relative_to(directory)):sha256(path) for path in sorted(directory.rglob('*')) if path.is_file()}


def await_process_exit(server,timeout=180):
    until=time.monotonic()+timeout
    while server.p.poll() is None and time.monotonic()<until: server.read(.25)
    assert server.p.poll() is not None,'Expected plugin-requested server shutdown did not complete'
    server.reader.join(timeout=5);server.drain();server.log.close();server.closed=True
    process_info=json.loads(server.process_record.read_text());process_info.update({'exited_at_utc':stamp(),'returncode':server.p.returncode,'stop_method':'plugin-requested hard-conflict shutdown'});write_json(server.process_record,process_info)
    return server.p.returncode


def hard_conflict(pin,expected_hash):
    variant='paper-26.3-hard-conflict';directory=ROOT/variant
    result={'variant':variant,'scenario':'external_root_guard_startup_observation','tested_jar_sha256':expected_hash,
            'started_at_utc':stamp(),'checks':[]}
    server=holder=None
    try:
        marker=named_fixture(variant,pin,25588)
        state=ROOT/(variant+'-state');state.mkdir()
        guard=state/(hashlib.sha256(str(directory).encode()).hexdigest()+'.server.lock')
        script='import fcntl,sys; f=open(sys.argv[1],"a+b"); fcntl.lockf(f,fcntl.LOCK_EX|fcntl.LOCK_NB); print("GUARD_READY",flush=True); sys.stdin.readline()'
        holder=subprocess.Popen([sys.executable,'-u','-c',script,str(guard)],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True)
        assert holder.stdout.readline().strip()=='GUARD_READY'
        before=world_hashes(directory/'world');write_json(LOGS/(variant+'-world-before.json'),before)
        server=Server(variant,'hard-conflict-startup')
        expect_key(server,'startup.guard',timeout=240)
        result['plugin_requested_shutdown_at_utc']=stamp()
        result['server_exit_code']=await_process_exit(server)
        assert holder.poll() is None,'Independent guard holder died before server exit'
        after=world_hashes(directory/'world');write_json(LOGS/(variant+'-world-after.json'),after)
        result['world_files_before']=len(before);result['world_files_after']=len(after)
        result['world_changed']=[name for name in sorted(before.keys()|after.keys()) if before.get(name)!=after.get(name)]
        result['world_content_changed_excluding_session_lock']=[name for name in result['world_changed'] if not name.endswith('session.lock')]
        events=[]
        for number,line in enumerate(server.lines):
            if re.search(r'Preparing level|Preparing start region|Loading.*world|Saving.*(?:world|chunk)|Saved the game|Stopping server|起動時の安全確認|サーバー停止を要求|Starting minecraft server|Done \(',ANSI.sub('',line),re.I):
                events.append({'line':number+1,'text':ANSI.sub('',line).strip()})
        result['ordered_startup_world_events']=events
        result['ready_done_logged']=any('Done (' in line for line in server.lines)
        result['world_load_log_observed']=any(re.search(r'Preparing level|Preparing start region|Loading.*world',e['text'],re.I) for e in events)
        result['world_save_log_observed']=any(re.search(r'Saving.*(?:world|chunk)',e['text'],re.I) for e in events)
        assert (directory/'vaultbackup-fixture.txt').read_bytes()==marker
        assert not (state/'active-restore.properties').exists()
        assert not any(re.search(pattern('startup.success'),ANSI.sub('',line)) for line in server.lines)
        result['checks']=['independent_external_guard_held_through_server_exit','hard_conflict_requested_shutdown',
                          'world_hash_and_load_save_boundary_observed','no_false_restore_success','fixture_marker_unchanged']
        result['scope_note']='Observed one exact supported build. A plugin shutdown request does not promise zero pre-plugin/startup writes.'
        result['observation_completed']=True
        result['prevention_guarantee']=False
        result['outcome']='Observed startup-write limitation; not a successful write-prevention test'
        result['passed_definition']='Measurement completed, not a zero-write or concurrent-startup safety guarantee'
        result['passed']=True;server=None
    except Exception as exc:
        result['passed']=False;result['error']=str(exc);traceback.print_exc()
        if server and server.p.poll() is None:
            try: result['failure_cleanup']=server.finish()
            except Exception as e:result['failure_cleanup_error']=str(e)
    finally:
        if holder and holder.poll() is None:
            holder.stdin.write('\n');holder.stdin.flush();holder.wait(timeout=10)
    result['completed_at_utc']=stamp();write_json(LOGS/'hard-conflict-startup-result.json',result)
    print('HARD CONFLICT RESULT '+json.dumps(result,ensure_ascii=False),flush=True)
    return result


def stale_waiting(pin,expected_hash):
    variant='paper-26.3-stale-waiting';directory=ROOT/variant
    result={'variant':variant,'scenario':'authenticated_dead_helper_waiting_job_startup','tested_jar_sha256':expected_hash,
            'started_at_utc':stamp(),'checks':[]}
    server=None
    try:
        marker=named_fixture(variant,pin,25589)
        server=Server(variant,'prepare-stale-waiting')
        server.expect(r'Done \(',timeout=240);settled(server)
        backup_id=create(server,'stale-startup-snapshot')
        mutation=b'Current data must survive stale waiting startup\n';(directory/'vaultbackup-fixture.txt').write_bytes(mutation)
        job=reserve(server,variant,backup_id)
        pid=int(props(job/'helper-process.properties')['pid'])
        command=(Path('/proc')/str(pid)/'cmdline').read_bytes().replace(b'\0',b' ').decode()
        assert str(job/'job.properties') in command,'Refuse to signal an unrelated PID'
        assert props(job/'status.properties')['status']=='WAITING_FOR_MANUAL_STOP'
        server.log.write(f'TEST FAULT [{stamp()}]: SIGTERM exact waiting helper PID {pid}; server remains running\n');server.log.flush()
        os.kill(pid,signal.SIGTERM);result['dead_helper']=helper_exited(job)
        server.stay_alive(2)
        assert (directory/'vaultbackup-fixture.txt').read_bytes()==mutation
        result['manual_stop_exit']=server.finish();server=None
        assert props(job/'status.properties')['status']=='WAITING_FOR_MANUAL_STOP'
        assert not (job/'receipt.properties').exists()
        before=world_hashes(directory/'world');write_json(LOGS/(variant+'-world-before-restart.json'),before)
        server=Server(variant,'reconcile-stale-waiting')
        expect_key(server,'startup.restorefailed',timeout=240)
        expect_key(server,'startup.enabled',timeout=240)
        server.expect(r'Done \(',timeout=240);settled(server)
        assert props(job/'status.properties')['status']=='CANCELLED'
        assert (job/'cancel.properties').is_file() and (job/'cancel-ack.properties').is_file()
        assert props(job/'cancel-ack.properties')['source']=='CONFIRMED_HELPER_DEATH'
        assert not (ROOT/(variant+'-state')/'active-restore.properties').exists()
        assert (directory/'vaultbackup-fixture.txt').read_bytes()==mutation
        job_properties=props(job/'job.properties')
        assert not Path(job_properties['stage']).exists() and not Path(job_properties['rollback']).exists()
        assert not (job/'receipt.properties').exists()
        assert not any(re.search(pattern('startup.guard'),ANSI.sub('',line)) for line in server.lines)
        catalogue(server,1)
        result['checks']=['real_reservation_authenticated_helper_metadata','exact_waiting_helper_terminated_server_left_alive',
                          'graceful_original_server_stop_without_swap','stale_waiting_restart_reaches_ready',
                          'authenticated_cancellation_acknowledged_dead_helper','active_marker_cleared','mutated_current_data_retained',
                          'no_staging_rollback_receipt_or_hard_shutdown','plugin_status_and_list_available']
        result['job_id']=job.name;result['final_stop_exit']=server.finish();server=None;result['passed']=True
    except Exception as exc:
        result['passed']=False;result['error']=str(exc);traceback.print_exc()
        try:result['failure_cleanup']=cleanup(server,variant)
        except Exception as e:result['failure_cleanup_error']=str(e)
    result['completed_at_utc']=stamp();write_json(LOGS/'stale-waiting-startup-result.json',result)
    print('STALE WAITING RESULT '+json.dumps(result,ensure_ascii=False),flush=True)
    return result

if __name__=='__main__':
    assert len(sys.argv)==2,'Provide frozen final JAR SHA-256'
    pin,inspection,config=pin_artifact(sys.argv[1]);load_messages(pin)
    results=[hard_conflict(pin,sys.argv[1]),stale_waiting(pin,sys.argv[1])]
    write_json(LOGS/'startup-boundary-results.json',{'tested_jar_sha256':sys.argv[1],'results':results,'ports_closed':ports_closed([25588,25589])})
    sys.exit(0 if all(r['passed'] for r in results) else 1)
