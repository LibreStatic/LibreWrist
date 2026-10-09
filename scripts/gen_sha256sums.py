#!/usr/bin/env python3
"""Generate apks/SHA256SUMS from apks/inputs.json (the canonical inputs manifest).

Usage:
  scripts/gen_sha256sums.py          rewrite apks/SHA256SUMS
  scripts/gen_sha256sums.py --check  exit 1 if apks/SHA256SUMS is out of date
  scripts/gen_sha256sums.py --verify-apks
                                     also compare package/version of the APKs present in
                                     APKS_DIR with the manifest (needs aapt2)
"""
import json
import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MANIFEST = os.path.join(ROOT, 'apks', 'inputs.json')
SUMS = os.path.join(ROOT, 'apks', 'SHA256SUMS')

REQUIRED = ('id', 'file', 'sha256', 'package', 'versionName', 'versionCode', 'minSdk',
            'abis', 'dpi', 'format', 'target', 'role', 'obtain')
TARGETS = ('phone', 'watch')
ROLES = ('patched', 'installed-stock')


def load(path=MANIFEST):
    with open(path) as f:
        return json.load(f)


def validate(manifest):
    """Return a list of problems (empty when the manifest is valid)."""
    errors = []
    ids, files = set(), set()
    for i, e in enumerate(manifest.get('inputs', [])):
        where = e.get('id', '#%d' % i)
        for k in REQUIRED:
            if k not in e:
                errors.append('%s: missing %s' % (where, k))
        if not re.fullmatch(r'[0-9a-f]{64}', str(e.get('sha256', ''))):
            errors.append('%s: sha256 is not 64 lowercase hex chars' % where)
        if e.get('target') not in TARGETS:
            errors.append('%s: target must be one of %s' % (where, TARGETS))
        if e.get('role') not in ROLES:
            errors.append('%s: role must be one of %s' % (where, ROLES))
        if e.get('format') != 'apk':
            errors.append('%s: only plain APKs are supported (format "apk")' % where)
        if not isinstance(e.get('versionCode'), int):
            errors.append('%s: versionCode must be an integer' % where)
        if e.get('id') in ids:
            errors.append('%s: duplicate id' % where)
        if e.get('file') in files:
            errors.append('%s: duplicate file' % where)
        ids.add(e.get('id'))
        files.add(e.get('file'))
    return errors


def render(manifest):
    return ''.join('%s  %s\n' % (e['sha256'], e['file']) for e in manifest['inputs'])


def verify_apks(manifest, apks_dir, aapt2):
    """Compare the badging of the APKs present in apks_dir with the manifest."""
    errors = []
    for e in manifest['inputs']:
        path = os.path.join(apks_dir, e['file'])
        if not os.path.isfile(path):
            continue
        out = subprocess.run([aapt2, 'dump', 'badging', path], capture_output=True,
                             text=True, check=True).stdout
        m = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", out)
        if not m:
            errors.append('%s: cannot read badging' % e['file'])
            continue
        got = (m.group(1), int(m.group(2)), m.group(3))
        want = (e['package'], e['versionCode'], e['versionName'])
        if got != want:
            errors.append('%s: APK is %s, manifest says %s' % (e['file'], got, want))
    return errors


def main(argv):
    manifest = load()
    errors = validate(manifest)
    if errors:
        sys.exit('apks/inputs.json is invalid:\n  ' + '\n  '.join(errors))
    text = render(manifest)
    if '--verify-apks' in argv:
        sdk = os.environ.get('ANDROID_HOME', os.path.expanduser('~/Android/Sdk'))
        bt = os.environ.get('BUILD_TOOLS_VERSION', '36.0.0')
        apks_dir = os.environ.get('APKS_DIR', os.path.join(ROOT, 'apks'))
        errors = verify_apks(manifest, apks_dir, os.path.join(sdk, 'build-tools', bt, 'aapt2'))
        if errors:
            sys.exit('\n'.join(errors))
    if '--check' in argv:
        with open(SUMS) as f:
            if f.read() != text:
                sys.exit('apks/SHA256SUMS is out of date; run scripts/gen_sha256sums.py')
        return
    with open(SUMS, 'w') as f:
        f.write(text)


if __name__ == '__main__':
    main(sys.argv[1:])
