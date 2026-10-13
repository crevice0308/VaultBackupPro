#!/usr/bin/env python3
"""Package audited public files only. Never uploads or publishes."""
from pathlib import Path
import hashlib,json,sys,zipfile
root=Path(__file__).resolve().parents[1]
out=Path(sys.argv[1]).resolve() if len(sys.argv)>1 else root/'dist'
out.mkdir(parents=True,exist_ok=True)
plugin=root/'build/libs/VaultBackupPro-1.0.0.jar'
helper=root/'build/libs/vaultbackup-restore-1.0.0.jar'
expected='1abf09736a5ea4bf1b363259a744c9ff050b7d73548db353e7066f357855c7ab'
assert hashlib.sha256(plugin.read_bytes()).hexdigest()==expected, 'Untested plugin bytes'
with zipfile.ZipFile(plugin) as z:assert z.read('vaultbackup-restore.jar')==helper.read_bytes()
# Frozen, audited source file allowlist; additions require an explicit release review.
allowed=(root/'release/PUBLIC-FILES.txt').read_text().splitlines()
source={}
for name in allowed:
 if not name or name.startswith('#'): continue
 path=Path(name)
 if path.is_absolute() or '..' in path.parts: raise SystemExit('Unsafe publication path')
 p=root/path
 if not p.is_file() or p.is_symlink(): raise SystemExit(f'Missing/unsafe publication input: {name}')
 source[name]=p
common={name:p for name,p in source.items() if
 ('/' not in name and p.suffix in {'.md','.txt'}) or
 name.startswith(('helpers/','third-party/','docs/','tools/runtime-tests/'))}
common['VaultBackupPro-1.0.0.jar']=plugin
common['helpers/vaultbackup-restore-1.0.0.jar']=helper
source.update(common)
manifest=[]
for kind,files in [('plugin',common),('source',source)]:
 base=f'VaultBackupPro-1.0.0-{kind}';dest=out/(base+'.zip')
 with zipfile.ZipFile(dest,'w',zipfile.ZIP_DEFLATED) as z:
  for name,p in sorted(files.items()):
   info=zipfile.ZipInfo(base+'/'+name,(2026,10,13,0,0,0));info.create_system=3
   info.external_attr=(0o100755 if p.name=='gradlew' or p.suffix=='.sh' else 0o100644)<<16
   z.writestr(info,p.read_bytes(),compress_type=zipfile.ZIP_DEFLATED,compresslevel=9)
 with zipfile.ZipFile(dest) as z:
  assert z.testzip() is None
  for name,p in files.items():assert z.read(base+'/'+name)==p.read_bytes()
 manifest.append({'name':dest.name,'sha256':hashlib.sha256(dest.read_bytes()).hexdigest(),'bytes':dest.stat().st_size})
p=out/plugin.name;p.write_bytes(plugin.read_bytes());manifest.append({'name':p.name,'sha256':expected,'bytes':p.stat().st_size})
(out/'SHA256SUMS.txt').write_text(''.join(f"{x['sha256']}  {x['name']}\n" for x in manifest))
print(json.dumps(manifest,indent=2))
