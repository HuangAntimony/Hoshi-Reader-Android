#!/usr/bin/env python3
"""Publish immutable optional components; never remove or replace existing assets."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import shutil
import tempfile
import urllib.request


def sha(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def download(url, target):
    with urllib.request.urlopen(url, timeout=30) as response, target.open('wb') as output:
        shutil.copyfileobj(response, output)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--catalog', required=True, type=Path)
    parser.add_argument('--directory', type=Path)
    parser.add_argument('--notice', type=Path)
    parser.add_argument('--extra', action='append', type=Path, default=[], help='Additional source/license asset')
    parser.add_argument('--target', help='Existing remote commit for the component tag')
    parser.add_argument('--check', action='store_true', help='Verify local payloads without publishing')
    parser.add_argument('--remote-check', action='store_true', help='Verify deployed URLs without publishing')
    args = parser.parse_args()
    catalog = json.loads(args.catalog.read_text())
    catalog = catalog.get('files', catalog)
    specs = [spec for files in catalog.values() for spec in files]
    tags = {spec['url'].split('/')[-2] for spec in specs}
    if len(tags) != 1:
        raise SystemExit('One immutable component release is required per catalog')
    tag = tags.pop()
    if args.remote_check:
        for spec in specs:
            with tempfile.TemporaryDirectory() as scratch:
                remote = Path(scratch) / spec['name']
                download(spec['url'], remote)
                if remote.stat().st_size != spec['bytes'] or sha(remote) != spec['sha256']:
                    raise SystemExit('Remote payload mismatch: ' + spec['name'])
        print('Verified remote component:', tag)
        return
    if args.directory is None or args.notice is None or (not args.check and not args.target):
        parser.error('--directory, --notice and --target are required for publication')
    payloads = []
    for spec in specs:
        path = args.directory / spec['name']
        if path.stat().st_size != spec['bytes'] or sha(path) != spec['sha256']:
            raise SystemExit('Payload mismatch: ' + str(path))
        payloads.append(path)
    payloads.append(args.notice)
    payloads.extend(args.extra)
    if args.check:
        print('Verified', len(specs), 'native files for', tag)
        return
    repo = 'HuangAntimony/Hoshi-Reader-Android'
    view = subprocess.run(['gh', 'release', 'view', tag, '--repo', repo, '--json', 'assets'], capture_output=True, text=True, timeout=60)
    if view.returncode:
        subprocess.run(['gh', 'release', 'create', tag, '--repo', repo, '--target', args.target,
                        '--prerelease', '--latest=false', '--title', 'Optional transcription components: ' + tag,
                        '--notes', 'Pinned native components downloaded and SHA-256 verified by Hoshi when needed. '
                        'This component release is excluded from app updates. See the attached notices for licenses and source links.'], check=True, timeout=120)
        existing = {}
    else:
        existing = {asset['name']: asset for asset in json.loads(view.stdout)['assets']}
    # A shared component may be referenced by many app versions. Never use --clobber:
    # deleting an old asset before a failed upload would break all of those versions.
    for path in payloads:
        if path.name in existing:
            with tempfile.TemporaryDirectory() as scratch:
                remote = Path(scratch) / path.name
                download(existing[path.name]['url'], remote)
                if sha(remote) != sha(path):
                    raise SystemExit('Published immutable asset differs: ' + path.name)
        else:
            subprocess.run(['gh', 'release', 'upload', tag, str(path), '--repo', repo], check=True, timeout=120)
        print('Verified published asset:', path.name, flush=True)


if __name__ == '__main__':
    main()
