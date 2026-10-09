#!/usr/bin/env python3
"""Maintain and sign the patch catalogue (catalogue/catalogue.json).

  catalogue.py check        validate the catalogue (schema, profile/pattern references, every
                            op and argument, inputs in sync with apks/inputs.json); exit 1 on
                            problems
  catalogue.py sync-inputs  copy the "inputs" of apks/inputs.json into the catalogue
  catalogue.py genkey       create the signing key ($CATALOGUE_KEY, ECDSA P-256, PKCS#8 PEM,
                            mode 600; refuses to overwrite) and write catalogue/catalogue.pub
  catalogue.py sign         serial += 1 (unless --no-bump), rewrite catalogue.json in the
                            canonical format and write catalogue/catalogue.json.sig (DER ECDSA,
                            P-256 / SHA-256: verifiable with SHA256withECDSA on Android)
  catalogue.py verify       check catalogue.json.sig against catalogue.pub

The catalogue is published and signed; it holds data only, never code. Uses the openssl CLI.
"""
import argparse
import json
import os
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, ROOT)
# gen_sha256sums lives next to this file; appended AFTER ROOT so that scripts/superpatcher.py
# never shadows the superpatcher package.
sys.path.append(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, 'scripts', 'lib'))

import config  # noqa: E402
import gen_sha256sums  # noqa: E402
from superpatcher import patterns as P  # noqa: E402

CAT_DIR = os.path.join(ROOT, 'catalogue')
CATALOGUE = os.path.join(CAT_DIR, 'catalogue.json')
SIG = os.path.join(CAT_DIR, 'catalogue.json.sig')
PUB = os.path.join(CAT_DIR, 'catalogue.pub')


def render(cat):
    """Canonical text of the catalogue: indent 2, key order preserved, trailing newline."""
    return json.dumps(cat, indent=2, ensure_ascii=False) + '\n'


def load_raw(path=CATALOGUE):
    with open(path, encoding='utf-8') as f:
        return json.load(f)


def write_catalogue(cat, path=CATALOGUE):
    with open(path, 'w', encoding='utf-8') as f:
        f.write(render(cat))


def problems(path=CATALOGUE, inputs_path=gen_sha256sums.MANIFEST):
    """Every problem of the catalogue at `path` (empty list = fine)."""
    try:
        cat = load_raw(path)
    except (OSError, ValueError) as e:
        return [f'cannot read {path}: {e}']
    out = P.validate_catalogue(cat)
    if out:
        return out
    manifest = gen_sha256sums.load(inputs_path)
    if cat['inputs'] != manifest['inputs']:
        out.append(f'inputs differ from {os.path.relpath(inputs_path, ROOT)} '
                   '(run scripts/catalogue.py sync-inputs)')
    return out


def cmd_check(args):
    found = problems(args.catalogue)
    for p in found:
        print('catalogue: ' + p, file=sys.stderr)
    if found:
        return 1
    cat = load_raw(args.catalogue)
    print(f'catalogue OK: serial {cat["serial"]}, {len(cat["patterns"])} patterns, '
          f'{len(cat["profiles"])} profiles')
    return 0


def cmd_sync_inputs(args):
    cat = load_raw(args.catalogue)
    cat['inputs'] = gen_sha256sums.load()['inputs']
    write_catalogue(cat, args.catalogue)
    print(f'inputs synced ({len(cat["inputs"])})')
    return 0


def openssl(*argv, **kw):
    try:
        return subprocess.run(['openssl', *argv], check=True, capture_output=True, **kw)
    except FileNotFoundError:
        raise SystemExit('openssl not found')
    except subprocess.CalledProcessError as e:
        raise SystemExit('openssl %s failed: %s' % (argv[0], e.stderr.decode(errors='replace')))


def cmd_genkey(args):
    key = args.key
    if os.path.exists(key):
        raise SystemExit(f'refusing to overwrite {key}')
    os.makedirs(os.path.dirname(key), exist_ok=True)
    ec = openssl('ecparam', '-name', 'prime256v1', '-genkey', '-noout').stdout
    pem = openssl('pkcs8', '-topk8', '-nocrypt', input=ec).stdout
    fd = os.open(key, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, 'wb') as f:
        f.write(pem)
    os.chmod(key, 0o600)
    pub = openssl('pkey', '-pubout', '-in', key).stdout
    with open(args.pub, 'wb') as f:
        f.write(pub)
    print(f'key: {key} (keep it private)\npublic key: {args.pub}')
    return 0


def sign_file(path, key, sig):
    openssl('dgst', '-sha256', '-sign', key, '-out', sig, path)


def verify_file(path, pub, sig):
    """True if `sig` is a valid signature of `path` under public key `pub`."""
    try:
        r = subprocess.run(['openssl', 'dgst', '-sha256', '-verify', pub, '-signature', sig, path],
                           capture_output=True)
    except FileNotFoundError:
        raise SystemExit('openssl not found')
    return r.returncode == 0


def cmd_sign(args):
    if not os.path.exists(args.key):
        raise SystemExit(f'missing signing key {args.key} (scripts/catalogue.py genkey)')
    found = problems(args.catalogue)
    if found:
        raise SystemExit('refusing to sign an invalid catalogue:\n  ' + '\n  '.join(found))
    cat = load_raw(args.catalogue)
    if not args.no_bump:
        cat['serial'] += 1
    write_catalogue(cat, args.catalogue)
    sign_file(args.catalogue, args.key, args.sig)
    print(f'signed: serial {cat["serial"]} -> {args.sig}')
    return 0


def cmd_verify(args):
    if verify_file(args.catalogue, args.pub, args.sig):
        print('signature OK')
        return 0
    print('signature INVALID', file=sys.stderr)
    return 1


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    ap.add_argument('--catalogue', default=CATALOGUE, help='catalogue file')
    ap.add_argument('--key', default=config.CFG['CATALOGUE_KEY'], help='private key (PEM)')
    ap.add_argument('--pub', default=PUB, help='public key (PEM)')
    ap.add_argument('--sig', default=None, help='signature file (default: <catalogue>.sig)')
    sub = ap.add_subparsers(dest='cmd', required=True)
    sub.add_parser('check')
    sub.add_parser('sync-inputs')
    sub.add_parser('genkey')
    s = sub.add_parser('sign')
    s.add_argument('--no-bump', action='store_true', help='keep the serial')
    sub.add_parser('verify')
    args = ap.parse_args(argv)
    args.sig = args.sig or args.catalogue + '.sig'
    return {'check': cmd_check, 'sync-inputs': cmd_sync_inputs, 'genkey': cmd_genkey,
            'sign': cmd_sign, 'verify': cmd_verify}[args.cmd](args)


if __name__ == '__main__':
    sys.exit(main())
