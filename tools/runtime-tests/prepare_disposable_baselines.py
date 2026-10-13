"""Seed disposable Paper/Purpur baselines from separately obtained official JARs.
This does not start a server. Read README.md and accept the Minecraft EULA first.
"""
from manualstop_harness import *

assert os.environ.get('VAULTBACKUP_TEST_EULA_ACCEPTED') == 'true', 'Minecraft EULA approval required'
files = {
    'paper-26.2': 'paper-26.2-133.jar',
    'paper-26.3': 'paper-26.3-178.jar',
    'purpur-26.2': 'purpur-26.2-2633.jar',
    'purpur-26.3': 'purpur-26.3-2648.jar',
}
with zipfile.ZipFile(SOURCE) as archive:
    template = archive.read('config.yml').decode('utf-8')
BASE.mkdir(parents=True, exist_ok=True)
for number, (variant, filename) in enumerate(files.items()):
    source = TOOLS / 'servers' / filename
    assert sha256(source) == SERVER_HASHES[variant], f'Unverified server JAR: {source}'
    directory = BASE / variant
    assert not directory.exists(), f'Refusing to replace existing fixture: {directory}'
    directory.mkdir()
    shutil.copy2(source, directory / 'server.jar')
    (directory / 'plugins/VaultBackupPro').mkdir(parents=True)
    config = template.replace('../VaultBackup-backups', '../' + variant + '-backups')
    config = config.replace('../VaultBackup-state', '../' + variant + '-state')
    config = config.replace('automatic:\n  enabled: true', 'automatic:\n  enabled: false')
    config = config.replace("    - 'cache/**'\n", '')
    (directory / 'plugins/VaultBackupPro/config.yml').write_text(config)
    (directory / 'eula.txt').write_text('# EULA accepted explicitly for these disposable fixtures.\neula=true\n')
    (directory / 'server.properties').write_text(f'''server-ip=127.0.0.1
server-port={25581 + number}
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
''')
    print('Created disposable baseline', directory)
