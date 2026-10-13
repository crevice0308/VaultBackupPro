"""Initialize fresh loopback-only empty test servers, without the product plugin."""
from direct_harness import *
import sys
FILES = {'paper-26.2':'paper-26.2-133.jar', 'paper-26.3':'paper-26.3-179.jar',
         'purpur-26.2':'purpur-26.2-2633.jar', 'purpur-26.3':'purpur-26.3-2648.jar',
         'spigot-26.2':'spigot-26.2.jar', 'spigot-26.3':'spigot-26.3.jar'}
BASELINES=BASE/'baselines'; BASELINES.mkdir(exist_ok=True)
BASELOGS=BASE/'baseline-logs'; BASELOGS.mkdir(exist_ok=True)
for variant in sys.argv[1:] or VARIANTS:
    directory=BASELINES/variant
    assert not directory.exists(), f'Preserve existing baseline: {directory}'
    jar=TOOLS/'servers'/FILES[variant]
    assert sha256(jar)==SERVER_HASHES[variant], f'Publisher checksum mismatch: {jar}'
    directory.mkdir(); (directory/'plugins').mkdir()
    shutil.copy2(jar,directory/'server.jar')
    (directory/'eula.txt').write_text('# Operator explicitly approved Minecraft EULA for isolated tests.\neula=true\n')
    (directory/'server.properties').write_text(f'''server-ip=127.0.0.1
server-port={25581+VARIANTS.index(variant)}
online-mode=false
max-players=2
view-distance=2
simulation-distance=2
level-name=world
level-seed=1
level-type=minecraft:flat
generate-structures=false
spawn-protection=0
enable-query=false
enable-rcon=false
enable-status=true
motd=VaultBackup Pro disposable integration test
enforce-secure-profile=false
max-tick-time=120000
pause-when-empty-seconds=-1
''')
    server=None; result={'variant':variant,'sha256':sha256(jar),'started_at_utc':stamp(),'plugin_installed':False}
    try:
        server=Server(variant,'initialization',directory,BASELOGS)
        server.expect(r'Done \(',timeout=300)
        index=server.cmd('save-all flush')
        server.expect(r'Saved the game|Saved the world',start=index,timeout=90)
        result['exit_code']=server.finish(); result['ready']=True
        assert (directory/'world/level.dat').is_file()
    except Exception as exc:
        result['ready']=False; result['error']=str(exc)
        if server and server.p.poll() is None:
            try: result['cleanup_exit']=server.finish()
            except Exception as cleanup: result['cleanup_error']=str(cleanup)
    result['finished_at_utc']=stamp(); write_json(BASELOGS/(variant+'-result.json'),result)
    print(json.dumps(result),flush=True)
    if not result['ready']: raise SystemExit(1)
