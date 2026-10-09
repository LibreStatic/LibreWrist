#!/usr/bin/env python3
"""Super-patcher CLI for the Samsung Health Monitor (SHM) APKs.

  superpatcher.py analyze <apk> [--profile gms] [--catalogue PATH]
      decode (read-only) and report what the profile would patch
  superpatcher.py patch <apk> -o <dir> [--profile gms] [--catalogue PATH] [--name N]
                        [--inject NAME=DIR ...]
      decode -> patch (profile order) -> inject extra smali -> apktool b -> <dir>/<name>-unsigned.apk

The patches and the profiles are data: catalogue/catalogue.json (default; see
docs/patches.md). A profile is an ORDERED list of pattern ids per family plus the names of the
extra smali the build injects. A listed pattern that does not match aborts the build.
Signing is done by scripts/build_shm_*.sh (sign_apk in scripts/lib/common.sh).

apktool runs serialised with the global lock ($WORK_DIR/.apktool.lock) and the heap from
config.env; temporary trees live under $WORK_DIR.
"""
import argparse
import os
import shutil
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, ROOT)
sys.path.insert(0, os.path.join(ROOT, 'scripts', 'lib'))

import config  # noqa: E402
from superpatcher import patterns as P  # noqa: E402

CFG = config.CFG
WORK_DIR = CFG['WORK_DIR']


def apktool(*args):
    os.makedirs(WORK_DIR, exist_ok=True)
    cmd = ['flock', os.path.join(WORK_DIR, '.apktool.lock'), 'java', CFG['APKTOOL_HEAP'],
           '-jar', CFG['APKTOOL_JAR'], *args]
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode != 0:
        print(r.stdout[-2000:], r.stderr[-2000:], file=sys.stderr)
        raise SystemExit(f'apktool {" ".join(args[:2])} failed')


def load_catalogue(path):
    try:
        return P.load_catalogue(path)
    except (OSError, ValueError) as e:
        raise SystemExit(f'cannot use the catalogue: {e}')


def load_profile(cat, name):
    try:
        return P.get_profile(cat, name)
    except KeyError as e:
        raise SystemExit(e.args[0])


def smali_dir_count(tree):
    return len([d for d in os.listdir(tree) if d == 'smali' or d.startswith('smali_classes')])


def inject_smali(tree, sources):
    """Merge the smali trees in `sources` into ONE new smali_classes{N+1} dir (N = existing
    smali dirs). Refuses to overwrite an existing file."""
    if not sources:
        return None
    dest = os.path.join(tree, f'smali_classes{smali_dir_count(tree) + 1}')
    os.makedirs(dest)
    for src in sources:
        for dp, _dn, fns in os.walk(src):
            for fn in fns:
                sp = os.path.join(dp, fn)
                tp = os.path.join(dest, os.path.relpath(sp, src))
                if os.path.exists(tp):
                    raise SystemExit(f'inject: {os.path.relpath(tp, tree)} already present')
                os.makedirs(os.path.dirname(tp), exist_ok=True)
                shutil.copy2(sp, tp)
    return dest


def report(family, results):
    print(f'\n=== family: {family} - {len(results)} patterns ===\n')
    total = 0
    for pid, desc, log in results:
        print(f' {"+" if log else "-"} {pid}: {desc}')
        for entry in log:
            print(f'    -> {entry}')
        total += len(log)
    print(f'\nTotal patched sites: {total}')


def decode_and_patch(apk, cat, profile, work):
    print(f'>> decoding {os.path.basename(apk)}')
    apktool('d', apk, '-o', work, '-f')
    family = P.detect_family(work, cat)
    print(f'>> family: {family}')
    ids = profile['families'].get(family)
    if ids is None:
        raise SystemExit(f'profile {profile["name"]!r} has no patch list for family {family!r}')
    try:
        results = P.run_profile(work, ids, catalogue=cat)
    except P.PatternRequiredError as e:
        report(family, e.report)
        raise SystemExit(str(e))
    report(family, results)
    return family


def fresh_work(tag):
    work = os.path.join(WORK_DIR, f'sp-{tag}-{os.getpid()}')
    shutil.rmtree(work, ignore_errors=True)
    os.makedirs(work)
    return work


def cmd_analyze(args):
    work = fresh_work('analyze')
    try:
        cat = load_catalogue(args.catalogue)
        decode_and_patch(os.path.abspath(args.apk), cat, load_profile(cat, args.profile), work)
    finally:
        shutil.rmtree(work, ignore_errors=True)


def cmd_patch(args):
    apk = os.path.abspath(args.apk)
    cat = load_catalogue(args.catalogue)
    profile = load_profile(cat, args.profile)
    out_dir = os.path.abspath(args.out)
    os.makedirs(out_dir, exist_ok=True)
    work = fresh_work('patch')
    try:
        family = decode_and_patch(apk, cat, profile, work)
        wanted = profile.get('inject', {}).get(family, [])
        given = dict(i.split('=', 1) for i in args.inject)
        if set(wanted) != set(given):
            raise SystemExit(f'profile {profile["name"]!r}/{family} injects {wanted}; '
                             f'--inject given: {sorted(given)}')
        dest = inject_smali(work, [os.path.abspath(given[n]) for n in wanted])
        if dest:
            print(f'>> injected {wanted} into {os.path.basename(dest)}')
        name = args.name or os.path.splitext(os.path.basename(apk))[0]
        unsigned = os.path.join(out_dir, name + '-unsigned.apk')
        print('>> rebuilding')
        apktool('b', work, '-o', unsigned)
        print(f'\n>> done: {unsigned}')
    finally:
        shutil.rmtree(work, ignore_errors=True)


def main():
    ap = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    sub = ap.add_subparsers(dest='cmd', required=True)
    a1 = sub.add_parser('analyze', help='show what the profile would patch (modifies nothing)')
    a2 = sub.add_parser('patch', help='decode + patch + inject + rebuild (unsigned)')
    a2.add_argument('-o', '--out', required=True)
    a2.add_argument('--name', default=None, help='output base name (<name>-unsigned.apk)')
    a2.add_argument('--inject', action='append', default=[], metavar='NAME=DIR',
                    help='smali tree to inject (names required by the profile)')
    for a in (a1, a2):
        a.add_argument('apk')
        a.add_argument('--profile', default='gms')
        a.add_argument('--catalogue', default=None,
                       help='catalogue file (default: catalogue/catalogue.json)')
    args = ap.parse_args()
    {'analyze': cmd_analyze, 'patch': cmd_patch}[args.cmd](args)


if __name__ == '__main__':
    main()
