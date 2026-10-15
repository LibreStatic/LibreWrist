"""Tests of scripts/catalogue.py: check on the real catalogue, input sync, key generation and
the sign / verify round trip with a throwaway key (needs the openssl CLI; temp data under
$WORK_DIR/test-tmp)."""
import copy
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
from superpatcher import patterns as P  # noqa: E402


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


class InstallSection(unittest.TestCase):
    """outputs / installOrder / grants / stockAppsToDisable: a future app turns these values
    into adb shell commands, so each class of bad entry must be rejected."""

    def setUp(self):
        self.cat = copy.deepcopy(C.load_raw())

    def problems(self):
        return P.validate_catalogue(self.cat)

    def assertRejected(self, needle):
        text = '\n'.join(self.problems())
        self.assertIn(needle, text)

    def test_real_catalogue_is_valid_and_complete(self):
        self.assertEqual(self.problems(), [])
        ids = [o['id'] for o in self.cat['outputs']]
        self.assertEqual(sorted(ids), sorted(s['output'] for s in self.cat['installOrder']))
        self.assertLessEqual(set(self.cat['grants']), set(ids))

    def test_outputs_structure(self):
        self.cat['outputs'].append(copy.deepcopy(self.cat['outputs'][0]))
        self.assertRejected('duplicate id')
        self.cat = copy.deepcopy(C.load_raw())
        self.cat['outputs'][0]['device'] = 'tablet'
        self.assertRejected('device must be one of')
        self.cat = copy.deepcopy(C.load_raw())
        self.cat['outputs'][0]['extra'] = 1
        self.assertRejected('expected id, device, package, from')

    def test_off_by_default_patterns(self):
        self.cat = copy.deepcopy(C.load_raw())
        off = self.cat['patterns']['shealth-oobe-local']
        self.assertTrue(off['optional'])
        self.assertTrue(off['offByDefault'])
        self.cat['patterns']['shealth-oobe-local']['offByDefault'] = 5
        self.assertRejected('offByDefault must be a boolean')
        self.cat = copy.deepcopy(C.load_raw())
        self.cat['patterns']['shealth-feature-dev-ui']['offByDefault'] = True
        self.assertRejected('offByDefault is only allowed on optional patterns')

    def test_trailing_newline_and_shell_chars_rejected(self):
        grants = self.cat['grants']['bridge-phone']
        perm = next(g for g in grants if g['kind'] == 'permission')
        perm['name'] = perm['name'] + '\n'
        self.assertRejected("field 'name' missing or not matching")
        self.cat = copy.deepcopy(C.load_raw())
        lst = next(g for g in self.cat['grants']['bridge-phone']
                   if g['kind'] == 'notificationListener')
        lst['component'] = '.Foo$Bar'
        self.assertRejected("field 'component' missing or not matching")
        self.cat = copy.deepcopy(C.load_raw())
        self.cat['outputs'][0]['package'] = 'com.samsung.\u00e9x'
        self.assertRejected('is not a valid package name')

    def test_package_names_are_strict(self):
        for bad in ('com.example; rm -rf /', 'nodots', 'com..x', '1com.x', 'com.x y', '',
                    '${OTHER}.x.y', '$(id).x.y', 'com.x/../y'):
            self.cat = copy.deepcopy(C.load_raw())
            self.cat['outputs'][0]['package'] = bad
            self.assertRejected('not a valid package name')
        self.cat = copy.deepcopy(C.load_raw())
        self.cat['outputs'][3]['package'] = '${PKG_PREFIX}.shmbridge.watch'
        self.assertEqual(self.problems(), [])

    def test_same_package_twice_on_one_device_is_rejected(self):
        self.cat['outputs'][3]['package'] = self.cat['outputs'][2]['package']
        self.assertRejected('repeated on device')

    def test_from_references(self):
        self.cat['outputs'][0]['from'] = {'input': 'nope', 'profile': 'gms'}
        self.assertRejected('is not in inputs')
        self.cat = copy.deepcopy(C.load_raw())
        self.cat['outputs'][0]['from'] = {'input': 'shm-phone', 'profile': 'nope'}
        self.assertRejected('is not in profiles')
        self.cat = copy.deepcopy(C.load_raw())
        self.cat['outputs'][0]['from'] = {'input': 'alarm-watch', 'profile': 'gms'}
        self.assertRejected('targets')                     # watch input for a phone output
        self.cat = copy.deepcopy(C.load_raw())
        self.cat['outputs'][0]['from'] = {'input': 'shm-phone', 'profile': 'alarm'}
        self.assertRejected('has no patterns for')
        self.cat = copy.deepcopy(C.load_raw())
        self.cat['outputs'][3]['from'] = {'ownApp': '../outside'}
        self.assertRejected('from.ownApp must match')
        self.cat = copy.deepcopy(C.load_raw())
        self.cat['outputs'][3]['from'] = {'ownApp': 'x', 'input': 'y'}
        self.assertRejected('from must be')
        self.cat = copy.deepcopy(C.load_raw())
        self.cat['outputs'][0]['from'] = {'input': ['x'], 'profile': {}}
        self.assertRejected('must be strings')

    def test_install_order_covers_every_output_exactly_once(self):
        self.cat['installOrder'].pop()
        self.assertRejected('is missing')
        self.cat = copy.deepcopy(C.load_raw())
        self.cat['installOrder'].append({'output': 'shm-watch'})
        self.assertRejected('listed twice')
        self.cat = copy.deepcopy(C.load_raw())
        self.cat['installOrder'].append({'output': 'ghost'})
        self.assertRejected('unknown output')
        self.cat = copy.deepcopy(C.load_raw())
        self.cat['installOrder'][0]['then'] = 'rm -rf'
        self.assertRejected('then must be one of')
        self.cat = copy.deepcopy(C.load_raw())
        self.cat['installOrder'][0]['extra'] = True
        self.assertRejected('expected {"output", "then"?}')

    def test_grants_keys_must_be_outputs(self):
        self.cat['grants']['ghost'] = []
        self.assertRejected('not an output id')

    def grant(self, g):
        self.cat['grants']['shm-phone'].append(g)
        return self.problems()

    def test_every_grant_kind_is_accepted(self):
        good = [
            {'kind': 'permission', 'name': 'android.permission.CAMERA'},
            {'kind': 'appop', 'op': 'FOO_BAR2', 'mode': 'allow', 'optional': True, 'note': 'x'},
            {'kind': 'notificationListener', 'component': '.Listener'},
            {'kind': 'notificationListener', 'component': 'com.example.a.InnerListener'},
            {'kind': 'deviceIdleWhitelist'},
            {'kind': 'secureSetting', 'key': 'doze_always_on', 'value': '0'},
            {'kind': 'secureSetting', 'key': 'k1', 'value': 'a.b-c_9'},
            {'kind': 'globalSetting', 'key': 'hidden_api_blacklist_exemptions',
             'value': 'Lcom/samsung/android/hardware/sensormanager/'},
        ]
        for g in good:
            self.cat = copy.deepcopy(C.load_raw())
            self.assertEqual(self.grant(g), [], g)

    def test_bad_grants_are_rejected(self):
        bad = [
            {'kind': 'shell', 'cmd': 'id'},                                    # unknown kind
            {'name': 'android.permission.CAMERA'},                              # no kind
            {'kind': ['permission']},                                           # unhashable kind
            {'kind': 'permission', 'name': 'android.permission.X; id'},
            {'kind': 'permission', 'name': 'a b'},
            {'kind': 'permission', 'name': '.leading'},
            {'kind': 'permission', 'name': 'android.permission.X', 'extra': 1},  # unknown field
            {'kind': 'permission'},                                             # missing field
            {'kind': 'appop', 'op': 'lower', 'mode': 'allow'},
            {'kind': 'appop', 'op': 'FOO BAR', 'mode': 'allow'},
            {'kind': 'appop', 'op': 'FOO', 'mode': 'deny'},
            {'kind': 'appop', 'op': 'FOO', 'mode': 'allow; id'},
            {'kind': 'appop', 'op': 'FOO'},
            {'kind': 'notificationListener', 'component': 'a/b'},
            {'kind': 'notificationListener', 'component': 'a b'},
            {'kind': 'notificationListener', 'component': '9x'},
            {'kind': 'notificationListener', 'component': 'A;id'},
            {'kind': 'notificationListener', 'component': 'BareClassName'},
            {'kind': 'deviceIdleWhitelist', 'package': 'x'},
            {'kind': 'secureSetting', 'key': 'Bad', 'value': '0'},
            {'kind': 'secureSetting', 'key': 'a; id', 'value': '0'},
            {'kind': 'secureSetting', 'key': 'k', 'value': ''},
            {'kind': 'secureSetting', 'key': 'k', 'value': 'x' * 65},
            {'kind': 'secureSetting', 'key': 'k', 'value': 'a b'},
            {'kind': 'secureSetting', 'key': 'k', 'value': '$(id)'},
            {'kind': 'secureSetting', 'key': 'k', 'value': '--user'},
            {'kind': 'deviceIdleWhitelist', 'optional': 'yes'},
            {'kind': 'deviceIdleWhitelist', 'note': 'two\nlines'},
            {'kind': 'deviceIdleWhitelist', 'note': ''},
        ]
        for g in bad:
            self.cat = copy.deepcopy(C.load_raw())
            self.assertTrue(self.grant(g), f'accepted: {g}')

    def test_duplicate_grant_is_rejected(self):
        self.cat['grants']['bridge-watch'].append(copy.deepcopy(self.cat['grants']['bridge-watch'][0]))
        self.assertRejected('duplicate grant')

    def test_stock_apps(self):
        stock = self.cat['stockAppsToDisable']
        self.assertTrue(stock)
        for field, bad in (('device', 'tablet'), ('package', 'x; id'), ('optional', 'yes'),
                           ('reason', '')):
            self.cat = copy.deepcopy(C.load_raw())
            self.cat['stockAppsToDisable'][0][field] = bad
            self.assertTrue(self.problems(), field)
        self.cat = copy.deepcopy(C.load_raw())
        del self.cat['stockAppsToDisable'][0]['reason']
        self.assertRejected('expected device, package, optional, reason')

    def test_top_level_keys_are_required(self):
        for k in ('outputs', 'installOrder', 'grants', 'stockAppsToDisable'):
            self.cat = copy.deepcopy(C.load_raw())
            del self.cat[k]
            self.assertRejected(f"missing top-level key '{k}'")

    def test_duplicate_json_keys_are_rejected(self):
        with self.assertRaises(P.CatalogueError):
            json.loads('{"schema": 1, "schema": 2}', object_pairs_hook=P.no_duplicate_keys)


class SchemaTwo(unittest.TestCase):
    """apps / strings / platform and the optional output, stock and grant fields."""

    def setUp(self):
        self.cat = copy.deepcopy(C.load_raw())

    def assertRejected(self, needle):
        self.assertIn(needle, '\n'.join(P.validate_catalogue(self.cat)))

    def app(self, app_id):
        return next(a for a in self.cat['apps'] if a['id'] == app_id)

    def test_real_catalogue_carries_the_new_data(self):
        self.assertEqual(self.cat['schema'], 2)
        self.assertEqual([a['id'] for a in self.cat['apps']],
                         ['health-monitor', 'alarm', 'bridge', 'samsung-health'])
        self.assertEqual(len(self.cat['strings']), 18)
        en = self.cat['strings']['en']
        for loc, table in self.cat['strings'].items():
            self.assertLessEqual(set(table), set(en), loc)

    def test_unknown_icon(self):
        self.app('alarm')['icon'] = 'rocket'
        self.assertRejected('icon must be one of')

    def test_output_in_two_apps_or_none(self):
        self.app('alarm')['outputs'].append('shm-phone')
        self.assertRejected("output 'shm-phone' is already in app 'health-monitor'")
        self.cat = copy.deepcopy(C.load_raw())
        self.app('alarm')['outputs'] = ['bridge-phone']
        self.assertRejected("output 'alarm-fork' is in no app")
        self.cat = copy.deepcopy(C.load_raw())
        self.app('alarm')['outputs'] = ['nope']
        self.assertRejected("unknown output 'nope'")

    def test_missing_required_english_key(self):
        del self.cat['strings']['en']['app.alarm.title']
        self.assertRejected("missing required key 'app.alarm.title'")
        self.cat = copy.deepcopy(C.load_raw())
        del self.cat['strings']['en']['output.shm-phone.label']
        self.assertRejected("missing required key 'output.shm-phone.label'")

    def test_unknown_why_key(self):
        self.cat['grants']['shm-phone'][0]['why'] = 'grant.why.nope'
        self.assertRejected("why 'grant.why.nope' is not in strings/en")
        self.cat = copy.deepcopy(C.load_raw())
        self.cat['grants']['shm-phone'][0]['why'] = 'Bad Key'
        self.assertRejected('why must be a key')

    def test_replaced_by_unknown_output(self):
        self.cat['stockAppsToDisable'][0]['replacedBy'] = 'nope'
        self.assertRejected("replacedBy 'nope' is not an output id")

    def test_locale_key_not_in_english(self):
        self.cat['strings']['es']['bogus.key'] = 'x'
        self.assertRejected("key 'bogus.key' is not in strings/en")

    def test_bad_string_values_and_locales(self):
        for bad in ('', 'a\nb', 'a\x00b', 5):
            self.cat = copy.deepcopy(C.load_raw())
            self.cat['strings']['es']['inject.watch-mod.title'] = bad
            self.assertRejected('single-line string')
        self.cat = copy.deepcopy(C.load_raw())
        self.cat['strings']['es_ES'] = {}
        self.assertRejected("locale 'es_ES'")
        self.cat = copy.deepcopy(C.load_raw())
        del self.cat['strings']['en']
        self.assertRejected('non-empty "en" table')

    def test_output_behaviours(self):
        out = next(o for o in self.cat['outputs'] if o['id'] == 'bridge-phone')
        out['start']['receiver'] = 'NoDot'
        self.assertRejected('start.receiver')
        out['start']['receiver'] = '.Ok'
        out['start']['action'] = '${OTHER}.x'
        self.assertRejected('start.action')
        out['start']['action'] = '${PKG_PREFIX}.x'
        out['singleCopy'] = 'yes'
        self.assertRejected('singleCopy must be a boolean')
        out['singleCopy'] = True
        out['statusAuthority'] = 'a b'
        self.assertRejected('statusAuthority')
        out['statusAuthority'] = '${PKG_PREFIX}.status'
        out['unknownField'] = 1
        self.assertRejected('expected id, device, package, from')

    def test_platform(self):
        hp = self.cat['platform']['healthPlatform']
        for field, bad in (('taps', 0), ('requiredBy', ['nope']), ('titleViewId', 'title'),
                           ('settingsComponent', 'a.b/Main'), ('package', 'x; id')):
            self.cat = copy.deepcopy(C.load_raw())
            self.cat['platform']['healthPlatform'][field] = bad
            self.assertRejected(f'platform.healthPlatform')
        self.cat = copy.deepcopy(C.load_raw())
        self.cat['platform']['logPackages']['watch'] = ['not a package']
        self.assertRejected('platform.logPackages.watch')
        self.cat = copy.deepcopy(C.load_raw())
        self.cat['platform']['revertSettings'][0]['default'] = '--user'
        self.assertRejected('default missing or not matching')
        self.cat = copy.deepcopy(C.load_raw())
        del self.cat['platform']['companionApp']
        self.assertRejected('platform: expected exactly')
        self.assertTrue(hp)


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
