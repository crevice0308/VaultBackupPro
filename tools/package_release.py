#!/usr/bin/env python3
"""Package the audited public files. Never uploads or publishes anything.

Usage: package_release.py [output-directory]

The version is read from build.gradle.kts. The JARs must have been built already
("gradlew release") and must be the tested bytes recorded in release/SHA256SUMS.txt.
"""
from pathlib import Path
import hashlib
import json
import re
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]
# One fixed entry time: identical inputs give identical archives, whenever they are packaged.
ENTRY_TIME = (1980, 2, 1, 0, 0, 0)


def fail(message):
    raise SystemExit('package_release: ' + message)


def project_version():
    text = (ROOT / 'build.gradle.kts').read_text(encoding='utf-8')
    match = re.search(r'^version\s*=\s*"([0-9]+\.[0-9]+\.[0-9]+)"\s*$', text, re.MULTILINE)
    if not match:
        fail('cannot read the project version from build.gradle.kts')
    return match.group(1)


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def recorded_hashes():
    hashes = {}
    for line in (ROOT / 'release/SHA256SUMS.txt').read_text(encoding='utf-8').splitlines():
        if line.strip():
            digest, name = line.split(None, 1)
            hashes[name.strip()] = digest
    return hashes


def public_files():
    names = []
    for line in (ROOT / 'release/PUBLIC-FILES.txt').read_text(encoding='utf-8').splitlines():
        name = line.strip()
        if not name or name.startswith('#'):
            continue
        path = Path(name)
        if path.is_absolute() or '..' in path.parts or '\\' in name:
            fail(f'unsafe publication path: {name}')
        names.append(name)
    if len(set(names)) != len(names):
        fail('release/PUBLIC-FILES.txt lists a path twice')
    return names


def tracked_files():
    """What git tracks, or None outside a git checkout (for example in an unpacked source archive)."""
    if not (ROOT / '.git').exists():
        return None
    try:
        output = subprocess.run(['git', '-C', str(ROOT), 'ls-files', '-z'], check=True, capture_output=True).stdout
    except (OSError, subprocess.CalledProcessError) as error:
        fail(f'git ls-files failed: {error}')
    return sorted(name for name in output.decode('utf-8').split('\0') if name)


def write_archive(destination, base, files):
    with zipfile.ZipFile(destination, 'w', zipfile.ZIP_DEFLATED) as archive:
        for name, path in sorted(files.items()):
            info = zipfile.ZipInfo(base + '/' + name, ENTRY_TIME)
            info.create_system = 3
            executable = path.name == 'gradlew' or path.suffix == '.sh'
            info.external_attr = (0o100755 if executable else 0o100644) << 16
            archive.writestr(info, path.read_bytes(), compress_type=zipfile.ZIP_DEFLATED, compresslevel=9)
    with zipfile.ZipFile(destination) as archive:
        if archive.testzip() is not None:
            fail(f'{destination.name} failed its integrity check')
        if sorted(archive.namelist()) != sorted(base + '/' + name for name in files):
            fail(f'{destination.name} does not contain exactly the intended files')
        for name, path in files.items():
            if archive.read(base + '/' + name) != path.read_bytes():
                fail(f'{destination.name}: {name} differs from its source')


def main():
    output = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else ROOT / 'dist'
    version = project_version()
    plugin = ROOT / f'build/libs/VaultBackupPro-{version}.jar'
    helper = ROOT / f'build/libs/vaultbackup-restore-{version}.jar'
    recorded = recorded_hashes()
    for jar in (plugin, helper):
        if not jar.is_file():
            fail(f'{jar.name} has not been built; run "gradlew release" first')
        if recorded.get(jar.name) != sha256(jar):
            fail(f'{jar.name} is not the tested build recorded in release/SHA256SUMS.txt')
    with zipfile.ZipFile(plugin) as archive:
        if archive.read('vaultbackup-restore.jar') != helper.read_bytes():
            fail('the helper inside the plugin JAR differs from the standalone helper')

    # The allowlist is reviewed for every release. Nothing tracked may be missing from it, and
    # nothing untracked may be published through it.
    names = public_files()
    tracked = tracked_files()
    if tracked is not None and sorted(names) != tracked:
        unlisted = sorted(set(tracked) - set(names)) or ['-']
        untracked = sorted(set(names) - set(tracked)) or ['-']
        fail('release/PUBLIC-FILES.txt differs from the tracked files. Tracked but not listed: '
             + ', '.join(unlisted) + '. Listed but not tracked: ' + ', '.join(untracked))
    source = {}
    for name in names:
        path = ROOT / name
        if not path.is_file() or path.is_symlink():
            fail(f'missing or unsafe publication input: {name}')
        source[name] = path

    built = {f'VaultBackupPro-{version}.jar': plugin, 'helpers/vaultbackup-restore.jar': helper}
    # What a server administrator needs: the documents, the licences and the two helper scripts.
    plugin_files = {name: path for name, path in source.items()
                    if ('/' not in name and path.suffix in {'.md', '.txt'})
                    or name.startswith(('helpers/', 'third-party/', 'docs/'))}
    plugin_files.update(built)
    source_files = dict(source)
    source_files.update(built)

    output.mkdir(parents=True, exist_ok=True)
    manifest = []
    for kind, files in (('plugin', plugin_files), ('source', source_files)):
        base = f'VaultBackupPro-{version}-{kind}'
        destination = output / (base + '.zip')
        write_archive(destination, base, files)
        manifest.append({'name': destination.name, 'sha256': sha256(destination), 'bytes': destination.stat().st_size})
    copy = output / plugin.name
    copy.write_bytes(plugin.read_bytes())
    manifest.append({'name': copy.name, 'sha256': sha256(copy), 'bytes': copy.stat().st_size})
    (output / 'SHA256SUMS.txt').write_text(''.join(f"{entry['sha256']}  {entry['name']}\n" for entry in manifest), encoding='utf-8')
    print(json.dumps(manifest, indent=2))


if __name__ == '__main__':
    main()
