"""Tests of scripts/catalogue.py: check on the real catalogue, input sync, key generation and
the sign / verify round trip with a throwaway key (needs the openssl CLI; temp data under
$WORK_DIR/test-tmp)."""
import json
import os
import shutil
import stat
import sys
import tempfile
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, ROOT)
sys.path.insert(0, os.path.join(ROOT, 'scripts', 'lib'))
sys.path.append(os.path.join(ROOT, 'scripts'))

import config  # noqa: E402
import catalogue as C  # noqa: E402


class RealCatalogue(unittest.TestCase):
    def test_check_passes(self):
        self.assertEqual(C.problems(), [])
        self.assertEqual(C.main(['check']), 0)

    def test_committed_signature_matches_when_present(self):
        if not (os.path.exists(C.SIG) and os.path.exists(C.PUB)):
            self.skipTest('catalogue is not signed yet')
        self.assertTrue(C.verify_file(C.CATALOGUE, C.PUB, C.SIG),
                        'catalogue.json changed after signing: run scripts/catalogue.py sign')

    def test_canonical_formatting(self):
        with open(C.CATALOGUE, encoding='utf-8') as f:
            text = f.read()
        self.assertEqual(text, C.render(json.loads(text)))


@unittest.skipUnless(shutil.which('openssl'), 'openssl not installed')
class SignRoundTrip(unittest.TestCase):
    def setUp(self):
        base = os.path.join(config.CFG['WORK_DIR'], 'test-tmp')
        os.makedirs(base, exist_ok=True)
        self.dir = tempfile.mkdtemp(prefix='catalogue-', dir=base)
        self.addCleanup(shutil.rmtree, self.dir, True)
        self.cat = os.path.join(self.dir, 'catalogue.json')
        self.key = os.path.join(self.dir, 'keys', 'k.pem')
        self.pub = os.path.join(self.dir, 'k.pub')
        shutil.copy(C.CATALOGUE, self.cat)
        self.args = ['--catalogue', self.cat, '--key', self.key, '--pub', self.pub]
        self.assertEqual(C.main(self.args + ['genkey']), 0)

    def run_cmd(self, *cmd):
        return C.main(self.args + list(cmd))

    def serial(self):
        return C.load_raw(self.cat)['serial']

    def test_genkey_format_and_no_overwrite(self):
        self.assertEqual(stat.S_IMODE(os.stat(self.key).st_mode), 0o600)
        with open(self.key) as f:
            self.assertTrue(f.read().startswith('-----BEGIN PRIVATE KEY-----'))  # PKCS#8
        with open(self.pub) as f:
            self.assertTrue(f.read().startswith('-----BEGIN PUBLIC KEY-----'))  # SPKI
        with self.assertRaises(SystemExit):
            self.run_cmd('genkey')

    def test_sign_bumps_serial_and_verifies(self):
        before = self.serial()
        self.assertEqual(self.run_cmd('sign'), 0)
        self.assertEqual(self.serial(), before + 1)
        with open(self.cat + '.sig', 'rb') as f:
            self.assertEqual(f.read(1), b'\x30')  # DER SEQUENCE (ECDSA-Sig-Value)
        self.assertEqual(self.run_cmd('verify'), 0)
        self.assertEqual(self.run_cmd('sign', '--no-bump'), 0)
        self.assertEqual(self.serial(), before + 1)
        self.assertEqual(self.run_cmd('verify'), 0)

    def test_tampering_and_other_key_fail_verification(self):
        self.run_cmd('sign')
        raw = C.load_raw(self.cat)
        raw['minAppVersion'] += 1
        C.write_catalogue(raw, self.cat)
        self.assertEqual(self.run_cmd('verify'), 1)
        self.run_cmd('sign')
        other = os.path.join(self.dir, 'other.pub')
        os.unlink(self.key)
        C.main(['--catalogue', self.cat, '--key', self.key, '--pub', other, 'genkey'])
        self.assertEqual(C.main(['--catalogue', self.cat, '--pub', other, 'verify']), 1)

    def test_sign_refuses_invalid_catalogue_and_missing_key(self):
        raw = C.load_raw(self.cat)
        raw['profiles']['gms']['families']['shm-watch'].append('does-not-exist')
        C.write_catalogue(raw, self.cat)
        with self.assertRaises(SystemExit):
            self.run_cmd('sign')
        os.unlink(self.key)
        with self.assertRaises(SystemExit):
            self.run_cmd('sign')

    def test_inputs_must_match_the_manifest(self):
        raw = C.load_raw(self.cat)
        raw['inputs'][0]['sha256'] = '0' * 64
        C.write_catalogue(raw, self.cat)
        self.assertTrue(any('inputs differ' in p for p in C.problems(self.cat)))
        self.assertEqual(self.run_cmd('check'), 1)
        self.assertEqual(self.run_cmd('sync-inputs'), 0)
        self.assertEqual(C.problems(self.cat), [])


if __name__ == '__main__':
    unittest.main()
