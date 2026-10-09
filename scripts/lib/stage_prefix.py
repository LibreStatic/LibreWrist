#!/usr/bin/env python3
"""Copy a source tree, replacing the package placeholder `shminterop` with PKG_PREFIX.

  stage_prefix.py <src_dir> <dst_dir>

- Text file contents (.java .xml .smali .kt .txt .json .properties .pro):
  `shminterop/` -> `<PKG_PATH>/` (smali descriptors, paths) and `shminterop` -> `<PKG_PREFIX>`.
- Paths: a directory component named `shminterop` becomes the PKG_PATH components
  (src/shminterop/ecg/X.java -> src/com/example/ecg/X.java).
- dst_dir is removed and recreated. With PKG_PREFIX=shminterop the copy equals the source.
"""
import os
import shutil
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from config import PKG_PATH, PKG_PREFIX, PLACEHOLDER  # noqa: E402

TEXT_EXT = {'.java', '.xml', '.smali', '.kt', '.txt', '.json', '.properties', '.pro'}


def rewrite_text(s):
    return s.replace(PLACEHOLDER + '/', PKG_PATH + '/').replace(PLACEHOLDER, PKG_PREFIX)


def map_rel(rel):
    parts = rel.split(os.sep)
    out = []
    for p in parts:
        out.extend(PKG_PATH.split('/') if p == PLACEHOLDER else [p])
    return os.path.join(*out) if out else rel


def stage(src, dst):
    src, dst = os.path.abspath(src), os.path.abspath(dst)
    shutil.rmtree(dst, ignore_errors=True)
    for dirpath, _dirs, files in os.walk(src):
        for f in files:
            sp = os.path.join(dirpath, f)
            rel = os.path.relpath(sp, src)
            dp = os.path.join(dst, map_rel(rel))
            os.makedirs(os.path.dirname(dp), exist_ok=True)
            if os.path.splitext(f)[1] in TEXT_EXT:
                with open(sp, encoding='utf-8') as fh:
                    data = fh.read()
                with open(dp, 'w', encoding='utf-8') as fh:
                    fh.write(rewrite_text(data))
            else:
                shutil.copy2(sp, dp)


if __name__ == '__main__':
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    stage(sys.argv[1], sys.argv[2])
    print(f'staged {sys.argv[1]} -> {sys.argv[2]} (PKG_PREFIX={PKG_PREFIX})')
