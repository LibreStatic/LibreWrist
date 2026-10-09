"""Shared configuration for the Python scripts.

Evaluates config.env with bash (same source as the shell scripts) and exposes the values.
"""
import os
import subprocess

SHM_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
_KEYS = ('PKG_PREFIX', 'ANDROID_HOME', 'BUILD_TOOLS_VERSION', 'PLATFORM_API', 'APKTOOL_JAR',
         'APKTOOL_HEAP', 'PSW_AAR', 'APKS_DIR', 'KEYSTORE', 'KEY_ALIAS', 'KS_PASS',
         'WORK_DIR', 'DIST_DIR', 'CATALOGUE_KEY')


def _load():
    script = ('SHM_ROOT="$1"; source "$SHM_ROOT/config.env"; '
              + ' '.join(f'printf "%s\\0" "${k}";' for k in _KEYS))
    out = subprocess.run(['bash', '-c', script, 'bash', SHM_ROOT], check=True,
                         capture_output=True, text=True, env=os.environ).stdout
    return dict(zip(_KEYS, out.split('\0')))


CFG = _load()
PKG_PREFIX = CFG['PKG_PREFIX']
PKG_PATH = PKG_PREFIX.replace('.', '/')
# Placeholder used by the source tree instead of the real prefix.
PLACEHOLDER = 'shminterop'


def smali_type(rel):
    """smali_type('relay/RelayTransport') -> 'L<prefix>/relay/RelayTransport;'"""
    return f'L{PKG_PATH}/{rel};'


def java_name(rel):
    """java_name('relay.RelayService') -> '<prefix>.relay.RelayService'"""
    return f'{PKG_PREFIX}.{rel}'
