#!/usr/bin/env python3
"""Package audited public files only. Never uploads or publishes.

Every gate is an explicit check (never `assert`, which `python -O` removes), every input
is read exactly once, and the manifest records the digests of the bytes actually written.
"""
from pathlib import Path
import hashlib, json, re, sys, zipfile

# Fixed timestamp for reproducible ZIP entries; update together with the release version.
RELEASE_TIMESTAMP = (2026, 11, 16, 0, 0, 0)


def fail(message: str) -> None:
    raise SystemExit(message)


def require(condition: bool, message: str) -> None:
    if not condition:
        fail(message)


root = Path(__file__).resolve().parents[1]
real_root = root.resolve(strict=True)


def checked_input(name: str) -> Path:
    """A regular file inside the repository, reached without any symlinked path component."""
    path = Path(name)
    require(bool(path.parts) and not path.is_absolute() and '..' not in path.parts, f'Unsafe publication path: {name}')
    current = root
    for part in path.parts:
        current = current / part
        require(not current.is_symlink(), f'Symlinked publication input: {name}')
    try:
        real = (root / path).resolve(strict=True)
    except OSError:
        fail(f'Missing publication input: {name}')
    require(real == real_root / path and real.is_file(), f'Missing/unsafe publication input: {name}')
    return real


declared = re.search(r"^version:\s*'?([0-9][0-9A-Za-z.+-]*)'?\s*$",
                     (root / 'src/main/resources/plugin.yml').read_text(encoding='utf-8'), re.M)
require(declared is not None, 'Cannot read the release version from plugin.yml')
version = declared.group(1)
require(re.search(rf"^version = '{re.escape(version)}'$", (root / 'build.gradle').read_text(encoding='utf-8'), re.M) is not None,
        'build.gradle and plugin.yml versions differ')

plugin_name = f'VaultBackupPro-{version}.jar'
helper_name = f'vaultbackup-restore-{version}.jar'
plugin_bytes = checked_input(f'build/libs/{plugin_name}').read_bytes()
helper_bytes = checked_input(f'build/libs/{helper_name}').read_bytes()

tested = {}
for line in (root / 'release/SHA256SUMS.txt').read_text(encoding='utf-8').splitlines():
    if not line.strip():
        continue
    fields = line.split()
    require(len(fields) == 2 and re.fullmatch(r'[0-9a-f]{64}', fields[0]) is not None, 'Malformed release/SHA256SUMS.txt line')
    require(fields[1] not in tested, f'Duplicate release/SHA256SUMS.txt entry: {fields[1]}')
    tested[fields[1]] = fields[0]
require(set(tested) == {plugin_name, helper_name}, f'release/SHA256SUMS.txt must list exactly {plugin_name} and {helper_name}')
require(hashlib.sha256(plugin_bytes).hexdigest() == tested[plugin_name], 'Untested plugin bytes')
require(hashlib.sha256(helper_bytes).hexdigest() == tested[helper_name], 'Untested helper bytes')
with zipfile.ZipFile(root / 'build/libs' / plugin_name) as jar:
    require(jar.read('vaultbackup-restore.jar') == helper_bytes, 'Embedded helper is not the tested standalone helper')
for wrapper in ('helpers/restore.sh', 'helpers/restore.cmd'):
    named = set(re.findall(r'vaultbackup-restore-[0-9][0-9A-Za-z.+-]*\.jar', (root / wrapper).read_text(encoding='utf-8')))
    require(named == {helper_name}, f'{wrapper} must name exactly {helper_name}, found {sorted(named)}')

out = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else root / 'dist'
out.mkdir(parents=True, exist_ok=True)

# Frozen, audited source file allowlist; additions require an explicit release review.
source = {}
for name in (root / 'release/PUBLIC-FILES.txt').read_text(encoding='utf-8').splitlines():
    if not name or name.startswith('#'):
        continue
    require(name not in source, f'Duplicate publication path: {name}')
    source[name] = checked_input(name).read_bytes()
common = {name: data for name, data in source.items() if
          ('/' not in name and Path(name).suffix in {'.md', '.txt'}) or
          name.startswith(('helpers/', 'third-party/', 'docs/', 'tools/runtime-tests/'))}
common[plugin_name] = plugin_bytes
common[f'helpers/{helper_name}'] = helper_bytes
source.update(common)

manifest = []
for kind, files in [('plugin', common), ('source', source)]:
    base = f'VaultBackupPro-{version}-{kind}'
    dest = out / (base + '.zip')
    with zipfile.ZipFile(dest, 'w', zipfile.ZIP_DEFLATED) as archive:
        for name, data in sorted(files.items()):
            info = zipfile.ZipInfo(base + '/' + name, RELEASE_TIMESTAMP)
            info.create_system = 3
            info.external_attr = (0o100755 if Path(name).name == 'gradlew' or name.endswith('.sh') else 0o100644) << 16
            archive.writestr(info, data, compress_type=zipfile.ZIP_DEFLATED, compresslevel=9)
    with zipfile.ZipFile(dest) as archive:
        require(archive.testzip() is None, f'Corrupt entry in {dest.name}')
        require(sorted(archive.namelist()) == sorted(base + '/' + name for name in files), f'Unexpected entries in {dest.name}')
        for name, data in files.items():
            require(archive.read(base + '/' + name) == data, f'Round-trip mismatch in {dest.name}: {name}')
    written = dest.read_bytes()
    manifest.append({'name': dest.name, 'sha256': hashlib.sha256(written).hexdigest(), 'bytes': len(written)})

standalone = out / plugin_name
standalone.write_bytes(plugin_bytes)
written = standalone.read_bytes()
digest = hashlib.sha256(written).hexdigest()
require(digest == tested[plugin_name], 'Written standalone plugin JAR does not match the tested bytes')
manifest.append({'name': plugin_name, 'sha256': digest, 'bytes': len(written)})
(out / 'SHA256SUMS.txt').write_text(''.join(f"{x['sha256']}  {x['name']}\n" for x in manifest), encoding='utf-8')
print(json.dumps(manifest, indent=2))
