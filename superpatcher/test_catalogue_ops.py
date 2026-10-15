"""Device-free tests of the catalogue interpreter and of the generic ops on synthetic smali /
manifest trees (temp data under $WORK_DIR/test-tmp)."""
import copy
import os
import shutil
import sys
import tempfile
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, ROOT)
sys.path.insert(0, os.path.join(ROOT, 'scripts', 'lib'))

import config  # noqa: E402
from superpatcher import engine as E  # noqa: E402
from superpatcher import patterns as P  # noqa: E402

ANDROID = 'xmlns:android="http://schemas.android.com/apk/res/android"'
MANIFEST = f'''<?xml version="1.0" encoding="utf-8"?>
<manifest {ANDROID} package="com.example.app">
    <uses-permission android:name="p.ONE"/>
    <uses-permission android:name="p.TWO"/>
    <permission android:name="com.example.app.perm" android:protectionLevel="signature"/>
    <application android:label="@string/app_name">
        <activity android:name="com.example.app.Main">
            <intent-filter>
                <action android:name="android.intent.action.MAIN"/>
                <category android:name="android.intent.category.LAUNCHER"/>
            </intent-filter>
            <intent-filter>
                <action android:name="android.intent.action.VIEW"/>
            </intent-filter>
        </activity>
        <service android:name="com.example.app.sync.SyncService"/>
        <receiver android:name="com.example.app.Boot"/>
        <meta-data android:name="m.one" android:value="1"/>
    </application>
</manifest>
'''

A_SMALI = '''.class public Lcom/example/util/A;
.super Ljava/lang/Object;

.method public static ok()Z
    .locals 1

    const-string v0, "MARK"

    return v0
.end method

.method public static other()Z
    .locals 1

    const/4 v0, 0x1

    return v0
.end method

.method public static valueOf()Lcom/example/util/E;
    .locals 1

    sget-object v0, Lcom/example/util/E;->a:Lcom/example/util/E;

    return-object v0
.end method

.method public static get()Lcom/example/util/E;
    .locals 2

    const/4 v0, 0x0

    return-object v0
.end method

.method public abstract nolocals()Z
.end method
'''

ENUM_SMALI = '''.class public final enum Lcom/example/util/E;
.super Ljava/lang/Enum;

.method static constructor <clinit>()V
    .locals 3

    const-string v0, "OTHER"

    sput-object v1, Lcom/example/util/E;->x:Lcom/example/util/E;

    const-string v0, "NOT_SUPPORTED"

    new-instance v1, Lcom/example/util/E;

    sput-object v1, Lcom/example/util/E;->g:Lcom/example/util/E;

    return-void
.end method
'''

STORED_SMALI = '''.class public Lcom/example/util/S;
.super Ljava/lang/Object;

.method public static stored()Lcom/example/util/E;
    .locals 2

    const-string v0, "Stored device version : "

    sget-object v0, Lcom/example/util/E;->x:Lcom/example/util/E;

    if-eq v1, v0, :ret

    return-object v1

    :ret
    return-object v0
.end method
'''


class Tree(unittest.TestCase):
    def setUp(self):
        base = os.path.join(config.CFG['WORK_DIR'], 'test-tmp')
        os.makedirs(base, exist_ok=True)
        self.tree = tempfile.mkdtemp(prefix='ops-', dir=base)
        self.addCleanup(shutil.rmtree, self.tree, True)

    def put(self, rel, text):
        p = os.path.join(self.tree, rel)
        os.makedirs(os.path.dirname(p), exist_ok=True)
        with open(p, 'w', encoding='utf-8') as f:
            f.write(text)
        return p

    def get(self, rel):
        with open(os.path.join(self.tree, rel), encoding='utf-8') as f:
            return f.read()

    def op(self, name, **args):
        func, kwargs = E.prepare_op(name, args)
        return func(self.tree, **kwargs)


class OpArguments(unittest.TestCase):
    def test_unknown_op(self):
        with self.assertRaises(E.OpArgError):
            E.prepare_op('getattr', {})

    def test_unknown_missing_and_mistyped_arguments(self):
        with self.assertRaisesRegex(E.OpArgError, 'unknown argument'):
            E.prepare_op('removeSmaliDir', {'rel': 'x', 'extra': 1})
        with self.assertRaisesRegex(E.OpArgError, 'missing'):
            E.prepare_op('removeSmaliDir', {})
        with self.assertRaises(E.OpArgError):
            E.prepare_op('removeSmaliDir', {'rel': 3})
        with self.assertRaises(E.OpArgError):
            E.prepare_op('swapConst', {'fileRegex': 'a', 'old': 'b', 'new': 'c', 'expect': 'many'})
        with self.assertRaises(E.OpArgError):
            E.prepare_op('manifestRemove', {'select': {'path': 'a', 'bogus': 1}, 'expect': 1})
        with self.assertRaises(E.OpArgError):
            E.prepare_op('manifestRemove', {'select': {'path': '//'}, 'expect': 1})

    def test_text_args_join_lines(self):
        _f, kw = E.prepare_op('insertAtMethodStart',
                              {'sigRegex': '.*', 'insert': ['', '    nop', '']})
        self.assertEqual(kw['insert'], '\n    nop\n')

    def test_every_registered_op_has_documented_semantics(self):
        for name, func in E.OPS.items():
            self.assertTrue(func.__doc__ and len(func.__doc__) > 40, name)


class Interpreter(Tree):
    def test_placeholders_are_expanded(self):
        self.assertEqual(P.expand({'a': ['${PKG_PREFIX}.x', 'L${PKG_PATH}/y;']}),
                         {'a': [config.PKG_PREFIX + '.x', f'L{config.PKG_PATH}/y;']})
        with self.assertRaises(E.OpArgError):
            P.expand('${HOME}')
        # no other templating: keys are not expanded, plain text is untouched
        self.assertEqual(P.expand({'${PKG_PREFIX}': '{{x}} $y'}), {'${PKG_PREFIX}': '{{x}} $y'})

    def catalogue(self, ops, optional=False):
        cat = copy.deepcopy(P.load_catalogue())
        cat['patterns'] = {'t': {'families': '*', 'title': 't', 'description': 'd',
                                 'optional': optional, 'ops': ops}}
        cat['profiles'] = {'p': {'description': 'd', 'families': {'shm-phone': ['t']},
                                 'inject': {}}}
        # the install section refers to the real profiles; this reduced catalogue has none
        out = copy.deepcopy(cat['outputs'][0])
        out['from']['profile'] = 'p'
        cat.update(outputs=[out], installOrder=[{'output': out['id']}],
                   grants={out['id']: []}, stockAppsToDisable=[])
        cat['apps'] = [{'id': 'syn', 'outputs': [out['id']], 'optional': False, 'icon': 'swap'}]
        cat['strings'] = {'en': {'app.syn.title': 'Syn', 'app.syn.summary': 'Synthetic',
                                 f'output.{out["id"]}.label': 'Out'}}
        cat['platform']['healthPlatform']['requiredBy'] = []
        cat['platform']['revertSettings'] = []
        self.assertEqual(P.validate_catalogue(cat), [])
        return cat

    def test_run_profile_report_shape_and_placeholder(self):
        self.put('smali/a/B.smali', f'x "{config.PKG_PREFIX}.y" z\n')
        cat = self.catalogue([{'op': 'replaceAllInFilesWithAnchor', 'fileContains': 'x',
                               'old': '${PKG_PREFIX}.y', 'new': 'Q'}])
        report = P.run_profile(self.tree, ['t'], catalogue=cat)
        self.assertEqual([(r[0], r[1]) for r in report], [('t', 'd')])
        self.assertTrue(report[0][2])
        self.assertIn('"Q"', self.get('smali/a/B.smali'))

    def test_empty_combined_log_fails_unless_optional(self):
        self.put('smali/a/B.smali', '.class LB;\n')
        ops = [{'op': 'swapConst', 'fileRegex': 'B', 'old': 'zzz', 'new': 'y', 'expect': 'any'}]
        with self.assertRaisesRegex(P.PatternRequiredError, 'matched nothing') as cm:
            P.run_profile(self.tree, ['t'], catalogue=self.catalogue(ops))
        self.assertEqual(cm.exception.report[0][0], 't')
        report = P.run_profile(self.tree, ['t'], catalogue=self.catalogue(ops, optional=True))
        self.assertEqual(report[0][2], [])

    def test_failing_step_aborts_with_step_number(self):
        self.put('smali/a/B.smali', '.class LB;\n')
        ops = [{'op': 'swapConst', 'fileRegex': 'B', 'old': 'zzz', 'new': 'y'}]
        with self.assertRaisesRegex(P.PatternRequiredError, 'raised.*step 1 .swapConst.'):
            P.run_profile(self.tree, ['t'], catalogue=self.catalogue(ops, optional=True))

    def test_unknown_pattern_id(self):
        with self.assertRaises(P.PatternRequiredError):
            P.run_profile(self.tree, ['nope'], catalogue=self.catalogue(
                [{'op': 'removeSmaliDir', 'rel': 'x'}]))

    def multi_catalogue(self):
        cat = self.catalogue([{'op': 'removeSmaliDir', 'rel': 'x'}])
        op = [{'op': 'swapConst', 'fileRegex': 'B', 'old': 'AAA', 'new': 'BBB'}]
        base = {'families': '*', 'title': 't', 'description': 'd', 'ops': op}
        cat['patterns'] = {
            'req': dict(base, optional=False),
            'opt': dict(base, optional=True),
            'ga': dict(base, optional=True, optionGroup='grp'),
            'gb': dict(base, optional=True, optionGroup='grp'),
        }
        cat['profiles']['p']['families']['shm-phone'] = ['req', 'opt', 'ga', 'gb']
        self.assertEqual(P.validate_catalogue(cat), [])
        return cat

    def test_skip_leaves_optional_patterns_out(self):
        self.put('smali/a/B.smali', '.class LB;\nAAA\n')
        cat = self.multi_catalogue()
        report = P.run_profile(self.tree, ['req', 'opt', 'ga', 'gb'], catalogue=cat,
                               skip=['opt', 'ga', 'gb'])
        self.assertEqual([r[0] for r in report], ['req'])

    def test_skip_refuses_required_unknown_and_partial_group(self):
        cat = self.multi_catalogue()
        for skip, text in ((['req'], 'required'), (['nope'], 'unknown'), (['ga'], 'as a whole')):
            with self.assertRaisesRegex(P.PatternRequiredError, text):
                P.run_profile(self.tree, ['req', 'opt', 'ga', 'gb'], catalogue=cat, skip=skip)

    def test_skip_does_not_weaken_anchors_of_the_rest(self):
        self.put('smali/a/B.smali', '.class LB;\n')
        with self.assertRaisesRegex(P.PatternRequiredError, 'req.*raised|matched nothing'):
            P.run_profile(self.tree, ['req', 'opt'], catalogue=self.multi_catalogue(), skip=['opt'])

    def test_option_group_validation(self):
        cat = self.multi_catalogue()
        cat['patterns']['req']['optionGroup'] = 'grp'
        self.assertTrue(any('only allowed on optional' in p for p in P.validate_catalogue(cat)))
        cat = self.multi_catalogue()
        cat['patterns']['ga']['optionGroup'] = 'Bad Group'
        self.assertTrue(any('kebab-case' in p for p in P.validate_catalogue(cat)))
        cat = self.multi_catalogue()
        cat['patterns']['ga']['optionGroup'] = 7
        self.assertTrue(any('kebab-case' in p for p in P.validate_catalogue(cat)))
        cat = self.multi_catalogue()
        cat['patterns']['gb']['optionGroup'] = 'other'
        self.assertTrue(any('at least two' in p for p in P.validate_catalogue(cat)))
        cat = self.multi_catalogue()
        cat['patterns']['ga']['extra'] = 1
        self.assertTrue(any('exactly the keys' in p for p in P.validate_catalogue(cat)))

    def test_real_catalogue_optional_set(self):
        pats = P.load_catalogue()['patterns']
        self.assertEqual({i for i, p in pats.items() if p['optional']}, {
            'shm-min-age-22-to-16', 'watch-min-age-22-to-16', 'shm-setup-network-gate', 'shm-sleep-apnea-support',
            'watch-apnea-country', 'watch-apnea-reeval-not-supported',
            'alarm-force-sound-vibrate', 'alarm-hide-launcher', 'alarm-relabel',
            'shealth-oobe-local', 'shealth-capability-gates',
            'shealth-launcher-badge', 'shm-launcher-badge'})
        self.assertEqual({i for i, p in pats.items() if p.get('offByDefault')},
                         {'shealth-oobe-local'})
        self.assertEqual({i for i, p in pats.items() if p.get('optionGroup') == 'sleep-apnea'},
                         {'shm-sleep-apnea-support', 'watch-apnea-country',
                          'watch-apnea-reeval-not-supported'})
        self.assertEqual({i for i, p in pats.items() if p.get('optionGroup') == 'minimum-age'},
                         {'shm-min-age-22-to-16', 'watch-min-age-22-to-16'})

    def test_validate_reports_bad_ops_and_references(self):
        cat = copy.deepcopy(P.load_catalogue())
        cat['patterns']['alarm-relabel']['ops'][0]['bogus'] = 1
        cat['patterns']['alarm-link-send']['ops'][0]['op'] = 'eval'
        del cat['patterns']['alarm-hide-launcher']['ops'][0]['select']
        cat['profiles']['alarm']['families']['alarm-watch'].append('missing-id')
        cat['profiles']['alarm']['families']['alarm-watch'].remove('alarm-provider-open')
        cat['patterns']['generic-vendor-check']['ops'][0]['constValue'] = '${NOPE}'
        text = '\n'.join(P.validate_catalogue(cat))
        for needle in ('unknown argument', "unknown op 'eval'", 'missing argument',
                       "unknown pattern 'missing-id'", 'alarm-provider-open: not used',
                       'unknown placeholder'):
            self.assertIn(needle, text)

    def test_real_catalogue_is_valid(self):
        self.assertEqual(P.validate_catalogue(P.load_catalogue()), [])

    def test_family_detection(self):
        cat = P.load_catalogue()
        pkg = 'com.samsung.android.shealthmonitor'
        self.put('AndroidManifest.xml', f'<manifest package="{pkg}"/>')
        self.assertEqual(P.detect_family(self.tree, cat), 'shm-watch')
        self.put('smali_classes3/com/samsung/android/shealthmonitor/version/api/'
                 'AppVersionCheckResponse.smali', '.class LA;\n')
        self.assertEqual(P.detect_family(self.tree, cat), 'shm-phone')
        self.put('AndroidManifest.xml', '<manifest package="com.samsung.android.watch.alarm"/>')
        self.assertEqual(P.detect_family(self.tree, cat), 'alarm-watch')
        self.put('AndroidManifest.xml', '<manifest package="other.app"/>')
        self.assertEqual(P.detect_family(self.tree, cat), 'unknown:other.app')


class MethodOps(Tree):
    def setUp(self):
        super().setUp()
        self.put('smali/com/example/util/A.smali', A_SMALI)
        self.put('smali/com/example/other/B.smali', A_SMALI.replace('util/A', 'other/B'))
        self.path = 'smali/com/example/util/A.smali'

    TRUE = ['', '    const/4 v0, 0x1', '', '    return v0', '']

    def test_insert_selectors_and_expect(self):
        log = self.op('insertAtMethodStart', fileRegex='/util/A\\.smali$', sigRegex='.*\\)Z',
                      bodyAll=['"MARK"'], insert=['', '    const/4 v0, 0x0', '', '    return v0', ''],
                      expect=1)
        self.assertEqual(len(log), 1)
        src = self.get(self.path)
        self.assertIn('ok()Z\n    .locals 1\n\n    const/4 v0, 0x0\n\n    return v0\n\n    const-string',
                      src)
        self.assertEqual(src.count('return v0'), 3)  # `other` untouched, `ok` patched once more
        self.assertEqual(self.get('smali/com/example/other/B.smali'),
                         A_SMALI.replace('util/A', 'other/B'))

    def test_expect_count_mismatch_fails(self):
        for expect in (2, 0):
            with self.assertRaises(RuntimeError):
                self.op('insertAtMethodStart', fileRegex='/util/A', sigRegex='.*\\)Z',
                        bodyAll=['"MARK"'], insert=self.TRUE, expect=expect)

    def test_default_expect_is_at_least_one(self):
        with self.assertRaisesRegex(RuntimeError, 'matched nothing'):
            self.op('insertAtMethodStart', sigRegex='.*nothing\\(\\)Z', insert=self.TRUE)
        self.assertEqual(self.op('insertAtMethodStart', sigRegex='.*nothing\\(\\)Z',
                                 insert=self.TRUE, expect='any'), [])

    def test_sig_is_a_full_match_and_sig_exclude_body_none(self):
        # fullmatch: a prefix of the header is not enough
        self.assertEqual(self.op('insertAtMethodStart', sigRegex='public static ok', insert=self.TRUE,
                                 expect='any'), [])
        log = self.op('insertAtMethodStart', fileRegex='util/A', sigRegex='public static .*\\)Z',
                      sigExclude=['other'], bodyNone=['"MARK"'], insert=self.TRUE, expect='any')
        self.assertEqual(log, [])  # `ok` has MARK, `other` is excluded, `nolocals` has no .locals
        log = self.op('insertAtMethodStart', fileRegex='util/A', sigRegex='public .*\\)Z',
                      sigExclude=['other', 'ok'], insert=self.TRUE, expect='any')
        self.assertEqual(log, [])  # only `nolocals` is selected and it cannot be patched

    def test_need_regs_tail_and_idempotency(self):
        args = dict(fileRegex='util/A', sigRegex='public static ok\\(\\)Z',
                    insert=['', '    nop', ''], needRegs=5,
                    tail=['    invoke-static {}, Lx;->t()V', '', ''], expect=1)
        self.op('insertAtMethodStart', **args)
        src = self.get(self.path)
        self.assertIn('ok()Z\n    .locals 5\n\n    nop\n', src)
        self.assertIn('"MARK"\n\n    invoke-static {}, Lx;->t()V\n\n    return v0\n.end method', src)
        self.assertEqual(self.op('insertAtMethodStart', **args)[0].split(' :: ')[0], self.path)
        self.assertEqual(self.get(self.path), src)  # second run changes nothing

    def test_return_singleton_uses_the_return_type(self):
        log = self.op('returnSingleton', fileRegex='util/A', sigRegex='public static get\\(\\).*',
                      field='a', expect=1)
        self.assertEqual(len(log), 1)
        self.assertIn('.locals 2\n\n    sget-object v0, Lcom/example/util/E;->a:'
                      'Lcom/example/util/E;\n\n    return-object v0\n\n    const/4 v0, 0x0',
                      self.get(self.path))
        with self.assertRaisesRegex(RuntimeError, 'object type'):
            self.op('returnSingleton', fileRegex='util/A', sigRegex='public static ok.*',
                    field='a')

    def test_set_method_body(self):
        self.op('setMethodBody', fileRegex='util/A', sigRegex='public static other\\(\\)Z',
                body=['    .locals 1', '', '    const/4 v0, 0x0', '', '    return v0'], expect=1)
        self.assertIn('.method public static other()Z\n    .locals 1\n\n    const/4 v0, 0x0\n\n'
                      '    return v0\n.end method', self.get(self.path))
        with self.assertRaises(RuntimeError):
            self.op('setMethodBody', sigRegex='public static other\\(\\)Z', body='x', expect=1)

    def test_insert_at_match_with_enum_field(self):
        self.put('smali/com/example/util/E.smali', ENUM_SMALI)
        self.put('smali/com/example/util/S.smali', STORED_SMALI)
        args = dict(
            fileRegex='util/S', sigRegex='public static \\w+\\(\\)L.*;',
            bodyAll=['"Stored device version : "'], expect=1,
            pattern='sget-object (?P<reg>v\\d), (?P<etype>L[\\w/]+;)->\\w+:(?P=etype)\\n\\n'
                    '    if-eq (?P<stored>v\\d), (?P=reg), :(?P<label>\\w+)\\n\\n'
                    '(?P<at>    return-object (?P=stored)\\n)',
            enumFields={'field': {'enumType': 'etype', 'constant': 'NOT_SUPPORTED'}},
            insert=['    sget-object {{reg}}, {{etype}}->{{field}}:{{etype}}', '',
                    '    if-eq {{stored}}, {{reg}}, :{{label}}', '', ''])
        # the enum type must be resolvable: the smali path is class E, type Lcom/example/util/E;
        self.op('insertInMethodAtMatch', **args)
        src = self.get('smali/com/example/util/S.smali')
        self.assertIn('    sget-object v0, Lcom/example/util/E;->g:Lcom/example/util/E;\n\n'
                      '    if-eq v1, v0, :ret\n\n    return-object v1', src)
        self.op('insertInMethodAtMatch', **args)  # idempotent
        self.assertEqual(self.get('smali/com/example/util/S.smali'), src)
        bad = dict(args, insert='{{nope}}')
        with self.assertRaisesRegex(RuntimeError, 'unknown template variable'):
            self.op('insertInMethodAtMatch', **bad)
        with self.assertRaisesRegex(RuntimeError, 'not found'):
            self.op('insertInMethodAtMatch', **dict(args, pattern='(?P<at>nomatch)'))
        with self.assertRaises(E.OpArgError):
            self.op('insertInMethodAtMatch', **dict(args, pattern='no named group'))


class FileOps(Tree):
    def test_replace_all_in_files_with_anchor(self):
        self.put('smali/a.smali', 'ANCHOR x x\n')
        self.put('smali/b.smali', 'x x\n')
        log = self.op('replaceAllInFilesWithAnchor', fileContains='ANCHOR', old='x', new='Z',
                      expect=1)
        self.assertEqual(len(log), 1)
        self.assertEqual(self.get('smali/a.smali'), 'ANCHOR Z Z\n')
        self.assertEqual(self.get('smali/b.smali'), 'x x\n')
        with self.assertRaises(RuntimeError):
            self.op('replaceAllInFilesWithAnchor', fileContains='NOPE', old='A', new='B')

    def test_swap_const_requires_exactly_one_occurrence_and_context(self):
        self.put('smali/x/c.smali', 'const/16 v0, 0x16\n')
        self.put('smali/x/d.smali', 'const/16 v0, 0x16\nconst/16 v0, 0x16\n')
        self.op('swapConst', fileRegex='/x/', old='const/16 v0, 0x16', new='const/16 v0, 0x10',
                expect=1)
        self.assertIn('0x10', self.get('smali/x/c.smali'))
        self.assertEqual(self.get('smali/x/d.smali').count('0x16'), 2)
        with self.assertRaises(RuntimeError):
            self.op('swapConst', fileRegex='/x/c', old='0x10', new='0x9', context='absent')

    def test_sweep_string_compare(self):
        self.put('smali/m/Check.smali',
                 'const-string v1, "samsung"\n\ninvoke-virtual {v2, v1}, '
                 'Ljava/lang/String;->contains(Ljava/lang/CharSequence;)Z\n')
        self.put('smali/androidx/Skip.smali',
                 'const-string v1, "samsung"\ninvoke-virtual {v2, v1}, '
                 'Ljava/lang/String;->equals(Ljava/lang/Object;)Z\n')
        self.op('sweepStringCompareAutoTrue', constValue='samsung', replaceValue='android',
                excludeRegex=['/androidx/'], expect=1)
        self.assertEqual(
            self.get('smali/m/Check.smali'),
            'const-string v1, "android"\n\ninvoke-virtual {v1, v1}, '
            'Ljava/lang/String;->contains(Ljava/lang/CharSequence;)Z\n')
        self.assertIn('"samsung"', self.get('smali/androidx/Skip.smali'))
        # only the listed comparison is considered
        with self.assertRaises(RuntimeError):
            self.op('sweepStringCompareAutoTrue', constValue='android', compare=['equals'])

    def test_replace_const_string_exact(self):
        self.put('smali/a.smali', 'const-string v0, "pkg"\nconst-string v0, "pkg.sub"\n')
        self.op('replaceConstStringExact', old='pkg', new='NEW', expect=1)
        self.assertEqual(self.get('smali/a.smali'),
                         'const-string v0, "NEW"\nconst-string v0, "pkg.sub"\n')

    def test_replace_const_string_exact_wide_registers_and_jumbo(self):
        self.put('smali/b.smali', 'const-string v12, "pkg"\nconst-string/jumbo p3, "pkg"\n')
        self.op('replaceConstStringExact', old='pkg', new='NEW', expect=1)
        self.assertEqual(self.get('smali/b.smali'),
                         'const-string v12, "NEW"\nconst-string/jumbo p3, "NEW"\n')

    def test_verify_class_contains(self):
        self.put('smali/a/B.smali', '.class La/B;\n.field m:J\n')
        self.op('verifyClassContains', checks=[{'className': 'a/B', 'contains': ' m:J'}])
        with self.assertRaisesRegex(RuntimeError, 'missing'):
            self.op('verifyClassContains', checks=[{'className': 'a/B', 'contains': 'zz'}])
        with self.assertRaisesRegex(RuntimeError, 'not found'):
            self.op('verifyClassContains', checks=[{'className': 'a/C', 'contains': 'zz'}])


class RenameFileOp(Tree):
    def rename(self, path, name):
        func, kwargs = E.prepare_op('renameFile', {'path': path, 'name': name})
        return func(self.tree, **kwargs)

    def test_changes_only_the_extension(self):
        self.put('res/drawable-xxhdpi/x.png', 'webp bytes')
        self.assertEqual(self.rename('res/drawable-xxhdpi/x.png', 'x.webp'),
                         ['res/drawable-xxhdpi/x.png :: renamed to x.webp'])
        self.assertEqual(self.get('res/drawable-xxhdpi/x.webp'), 'webp bytes')
        with self.assertRaises(RuntimeError):  # the source is gone now
            self.rename('res/drawable-xxhdpi/x.png', 'x.webp')

    def test_refuses_another_stem_and_existing_target(self):
        self.put('res/drawable/y.png', 'a')
        self.put('res/drawable/y.webp', 'b')
        for bad in ('z.webp', 'y', 'sub/y.webp'):
            with self.assertRaises(RuntimeError):
                self.rename('res/drawable/y.png', bad)
        with self.assertRaises(RuntimeError):
            self.rename('res/drawable/y.png', 'y.webp')
        self.assertEqual(self.get('res/drawable/y.png'), 'a')


class ResXmlReplaceStringOp(Tree):
    SYNC = ('<?xml version="1.0" encoding="utf-8"?>\n'
            '<sync-adapter android:contentAuthority="com.x.datastore" '
            'android:accountType="com.x.auth" android:other="com.x.datastore.sub"/>\n')

    def run_op(self, old, new, **kw):
        return self.op('resXmlReplaceString', old=old, new=new, **kw)

    def test_replaces_exact_values_only(self):
        self.put('res/xml/syncadapter.xml', self.SYNC)
        self.put('res/xml-v21/other.xml', self.SYNC)
        self.put('res/xml/untouched.xml', '<a android:b="nothing"/>\n')
        self.put('res/values/strings.xml', '<resources><string name="a" x="com.x.auth"/></resources>\n')
        self.put('AndroidManifest.xml', '<manifest a="com.x.auth"/>\n')
        done = self.run_op('com.x.auth', 'com.y.auth', expect=2)
        self.assertEqual(sorted(done), ['res/xml-v21/other.xml :: com.x.auth',
                                        'res/xml/syncadapter.xml :: com.x.auth'])
        out = self.get('res/xml/syncadapter.xml')
        self.assertIn('accountType="com.y.auth"', out)
        self.assertIn('contentAuthority="com.x.datastore"', out)
        self.assertEqual(self.get('res/xml/untouched.xml'), '<a android:b="nothing"/>\n')
        self.assertIn('com.x.auth', self.get('res/values/strings.xml'))
        self.assertEqual(self.get('AndroidManifest.xml'), '<manifest a="com.x.auth"/>\n')

    def test_substring_value_is_not_replaced(self):
        self.put('res/xml/s.xml', self.SYNC)
        with self.assertRaisesRegex(RuntimeError, 'matched nothing'):
            self.run_op('com.x.data', 'com.y.data')
        self.assertEqual(self.get('res/xml/s.xml'), self.SYNC)

    def test_expect_mismatch_fails_and_escapes(self):
        self.put('res/xml/s.xml', '<a v="x&amp;y"/>\n')
        with self.assertRaisesRegex(RuntimeError, 'expected 2'):
            self.run_op('x&y', 'z', expect=2)
        self.put('res/xml/s.xml', '<a v="x&amp;y"/>\n')
        self.run_op('x&y', 'a"b<c', expect=1)
        self.assertEqual(self.get('res/xml/s.xml'), '<a v="a&quot;b&lt;c"/>\n')


class AdaptiveIconBadgeOp(Tree):
    NS = 'xmlns:android="http://schemas.android.com/apk/res/android"'
    ICON = ('<?xml version="1.0" encoding="utf-8"?>\n<adaptive-icon %s>\n'
            '    <background android:drawable="@color/bg" />\n'
            '    <foreground android:drawable="@mipmap/fg" />\n'
            '%s</adaptive-icon>\n')
    MONO = '    <monochrome android:drawable="@mipmap/mono" />\n'
    LAYERS = {
        'foreground': [
            {'pathData': 'M1,1', 'fillColor': '#FF4F5BD5', 'strokeColor': '#FFFFFFFF',
             'strokeWidth': 1.5},
            {'pathData': 'M2,2', 'strokeWidth': 1, 'strokeLineCap': 'round',
             'strokeLineJoin': 'bevel'}],
        'monochrome': [{'pathData': 'M1,1', 'strokeColor': '#FFFFFFFF'}]}

    def run_op(self, layers=None, **kw):
        return self.op('adaptiveIconBadge', layers=layers or self.LAYERS, **kw)

    def test_wraps_the_drawable_and_adds_the_vector(self):
        self.put('res/mipmap-anydpi-v26/ic.xml', self.ICON % (self.NS, self.MONO))
        self.put('res/mipmap-anydpi-v26/plain.xml', self.ICON % (self.NS, ''))
        self.put('res/drawable/other.xml', '<vector %s/>\n' % self.NS)
        done = self.run_op(expect=2)
        self.assertEqual(done, ['res/mipmap-anydpi-v26/ic.xml :: foreground,monochrome',
                                'res/mipmap-anydpi-v26/plain.xml :: foreground'])
        import xml.etree.ElementTree as ET
        an = '{http://schemas.android.com/apk/res/android}'
        root = ET.parse(os.path.join(self.tree, 'res/mipmap-anydpi-v26/ic.xml')).getroot()
        self.assertEqual([c.tag for c in root], ['background', 'foreground', 'monochrome'])
        self.assertEqual(root.find('background').attrib, {an + 'drawable': '@color/bg'})
        fg = root.find('foreground')
        self.assertEqual(fg.attrib, {})
        items = list(fg.find('layer-list'))
        self.assertEqual([i.tag for i in items], ['item', 'item'])
        self.assertEqual(items[0].attrib, {an + 'drawable': '@mipmap/fg'})
        vector = items[1].find('vector')
        self.assertEqual(vector.attrib, {an + 'width': '108dp', an + 'height': '108dp',
                                         an + 'viewportWidth': '108',
                                         an + 'viewportHeight': '108'})
        paths = list(vector)
        self.assertEqual(list(paths[0].attrib.items()), [
            (an + 'pathData', 'M1,1'), (an + 'fillColor', '#FF4F5BD5'),
            (an + 'strokeColor', '#FFFFFFFF'), (an + 'strokeWidth', '1.5')])
        self.assertEqual(list(paths[1].attrib.items()), [
            (an + 'pathData', 'M2,2'), (an + 'strokeWidth', '1'),
            (an + 'strokeLineCap', 'round'), (an + 'strokeLineJoin', 'bevel')])
        mono = list(root.find('monochrome').find('layer-list'))
        self.assertEqual(mono[0].attrib, {an + 'drawable': '@mipmap/mono'})
        # the other file was not an adaptive icon
        self.assertEqual(self.get('res/drawable/other.xml'), '<vector %s/>\n' % self.NS)

    def test_inline_child_moves_into_the_first_item(self):
        self.put('res/drawable/w.xml', (
            '<adaptive-icon %s>\n    <foreground>\n'
            '        <inset android:drawable="@mipmap/fg" android:insetLeft="18dp" />\n'
            '    </foreground>\n</adaptive-icon>\n') % self.NS)
        self.run_op({'foreground': [{'pathData': 'M1,1'}]}, expect=1)
        out = self.get('res/drawable/w.xml')
        self.assertIn('<item>\n                <inset android:drawable="@mipmap/fg" '
                      'android:insetLeft="18dp" />\n            </item>', out)
        self.assertEqual(out.count('<inset'), 1)
        self.assertEqual(out.count('<layer-list>'), 1)

    def test_missing_layers_and_expect(self):
        self.put('res/mipmap/ic.xml', self.ICON % (self.NS, ''))
        with self.assertRaisesRegex(RuntimeError, 'matched nothing'):
            self.run_op({'monochrome': [{'pathData': 'M1,1'}]})
        with self.assertRaisesRegex(RuntimeError, 'expected 2'):
            self.run_op(expect=2)

    def test_already_patched_and_odd_layers_fail(self):
        self.put('res/mipmap/ic.xml', self.ICON % (self.NS, ''))
        self.run_op(expect=1)
        with self.assertRaisesRegex(RuntimeError, 'already carries this badge'):
            self.run_op()
        self.put('res/mipmap/ic.xml', (
            '<adaptive-icon %s><foreground/></adaptive-icon>\n') % self.NS)
        with self.assertRaisesRegex(RuntimeError, 'exactly one child'):
            self.run_op()

    def test_arguments_are_validated(self):
        bad = [{}, {'background': [{'pathData': 'M'}]}, {'foreground': []},
               {'foreground': [{'fillColor': '#FF000000'}]},
               {'foreground': [{'pathData': 'M', 'fillColor': '#000000'}]},
               {'foreground': [{'pathData': 'M', 'strokeLineCap': 'flat'}]},
               {'foreground': [{'pathData': 'M', 'strokeLineJoin': 'x'}]},
               {'foreground': [{'pathData': 'M', 'strokeWidth': '1'}]},
               {'foreground': [{'pathData': 'M', 'strokeWidth': True}]},
               {'foreground': [{'pathData': 'M', 'opacity': 1}]}]
        for layers in bad:
            with self.assertRaises(E.OpArgError, msg=layers):
                E.prepare_op('adaptiveIconBadge', {'layers': layers})
        with self.assertRaises(E.OpArgError):
            E.prepare_op('adaptiveIconBadge', {})


class ResourceEntryOps(Tree):
    PUBLIC = ('<?xml version="1.0" encoding="utf-8"?>\n<resources>\n'
              '    <public type="anim" name="fade" id="0x7f010001" />\n'
              '    <public type="string" name="app_name" id="0x7f020000" />\n</resources>\n')

    def setUp(self):
        super().setUp()
        self.put('res/values/public.xml', self.PUBLIC)

    def define(self, **kw):
        args = {'type': 'anim', 'name': 'anchor', 'id': '0x7f010000'}
        args.update(kw)
        func, kwargs = E.prepare_op('defineResourceEntry', args)
        return func(self.tree, **kwargs)

    def test_defines_public_and_null_value(self):
        self.define()
        self.assertIn('<public type="anim" name="anchor" id="0x7f010000" />', self.get('res/values/public.xml'))
        self.assertIn('<item type="anim" name="anchor">@null</item>', self.get('res/values/anims.xml'))

    def test_appends_to_existing_values_file(self):
        self.put('res/values/anims.xml', '<resources>\n    <item type="anim" name="x">@null</item>\n</resources>\n')
        self.define()
        v = self.get('res/values/anims.xml')
        self.assertEqual(v.count('<item'), 2)
        self.assertIn('name="anchor"', v)

    def test_applying_twice_fails(self):  # the second run sees the id (and the name) as taken
        self.define()
        with self.assertRaises(RuntimeError):
            self.define()

    def test_id_taken(self):
        with self.assertRaises(RuntimeError):
            self.define(id='0x7f010001')

    def test_name_taken(self):
        with self.assertRaises(RuntimeError):
            self.define(name='fade')
        self.assertNotIn('fade', self.get('res/values/public.xml').replace('name="fade"', ''))

    def test_wrong_type_id(self):
        with self.assertRaises(RuntimeError):
            self.define(id='0x7f090000')
        with self.assertRaises(RuntimeError):
            self.define(type='color')
        self.assertFalse(os.path.exists(os.path.join(self.tree, 'res/values/anims.xml')))

    def test_id_format(self):
        for bad in ('7f010000', '0x7f01', '0x7f01000g'):
            with self.assertRaises(E.OpArgError):
                self.define(id=bad)


class ManifestOps(Tree):
    def setUp(self):
        super().setUp()
        self.put('AndroidManifest.xml', MANIFEST)

    def names(self, tag):
        import xml.etree.ElementTree as ET
        root = ET.parse(os.path.join(self.tree, 'AndroidManifest.xml')).getroot()
        ns = '{http://schemas.android.com/apk/res/android}name'
        return [e.get(ns) for e in root.iter(tag)]

    def test_remove_each_listed(self):
        self.op('manifestRemove', select={'path': 'uses-permission',
                                          'attrIn': {'android:name': ['p.ONE', 'p.TWO']}},
                expect='eachListed')
        self.assertEqual(self.names('uses-permission'), [])
        with self.assertRaises(RuntimeError):  # one listed value is not there any more
            self.op('manifestRemove', select={'path': 'application/meta-data',
                                              'attrIn': {'android:name': ['m.one', 'm.two']}},
                    expect='eachListed')
        self.assertEqual(self.names('meta-data'), ['m.one'])  # the failed op wrote nothing

    def test_simple_name_over_several_paths(self):
        self.op('manifestRemove', select={
            'path': ['application/service', 'application/receiver'],
            'simpleNameIn': {'android:name': ['SyncService', 'Boot']}}, expect='eachListed')
        self.assertEqual(self.names('service') + self.names('receiver'), [])
        self.assertEqual(self.names('activity'), ['com.example.app.Main'])

    def test_counts_are_asserted(self):
        sel = {'path': 'uses-permission'}
        self.op('manifestExpect', select=sel, expect=2)
        self.op('manifestExpect', select=sel, expect='atLeastOne')
        for expect in (1, 3, 0):
            with self.assertRaises(RuntimeError):
                self.op('manifestExpect', select=sel, expect=expect)
        with self.assertRaises(RuntimeError):
            self.op('manifestRemove', select={'path': 'service'}, expect=1)  # not a child of root

    def test_set_attr_on_root_and_by_attribute(self):
        self.op('manifestSetAttr', select={'path': '.', 'attr': {'package': 'com.example.app'}},
                attrs={'package': 'new.pkg'}, expect=1)
        self.op('manifestSetAttr', select={'path': 'application',
                                           'attr': {'android:label': '@string/app_name'}},
                attrs={'android:label': '@string/x', 'android:icon': '@mipmap/i'}, expect=1)
        m = self.get('AndroidManifest.xml')
        self.assertIn('package="new.pkg"', m)
        self.assertIn('android:label="@string/x"', m)
        self.assertIn('android:icon="@mipmap/i"', m)
        with self.assertRaises(RuntimeError):  # the old value no longer matches
            self.op('manifestSetAttr', select={'path': '.', 'attr': {'package': 'com.example.app'}},
                    attrs={'package': 'x'}, expect=1)

    def test_remove_attr_requires_the_attribute(self):
        sel = {'path': 'permission', 'attr': {'android:name': 'com.example.app.perm'}}
        self.op('manifestRemoveAttr', select=sel, attrs=['android:protectionLevel'], expect=1)
        self.assertNotIn('protectionLevel', self.get('AndroidManifest.xml'))
        with self.assertRaisesRegex(RuntimeError, 'has no'):
            self.op('manifestRemoveAttr', select=sel, attrs=['android:protectionLevel'], expect=1)

    def test_child_match_selects_only_the_launcher_filter(self):
        sel = {'path': 'application/activity/intent-filter',
               'parentAttr': {'android:name': 'com.example.app.Main'},
               'hasChild': [{'tag': 'category',
                             'attr': {'android:name': 'android.intent.category.LAUNCHER'}},
                            {'tag': 'action',
                             'attr': {'android:name': 'android.intent.action.MAIN'}}]}
        self.op('manifestRemove', select=sel, expect=1)
        m = self.get('AndroidManifest.xml')
        self.assertNotIn('LAUNCHER', m)
        self.assertIn('android.intent.action.VIEW', m)
        with self.assertRaises(RuntimeError):
            self.op('manifestRemove', select=sel, expect=1)
        self.assertEqual(self.get('AndroidManifest.xml'), m)

    def test_add_child_with_nested_children_and_absence_check(self):
        name = 'android:name'
        self.op('manifestExpect', select={'path': '**', 'attr': {name: 'com.example.app.New'}},
                expect=0)
        self.op('manifestAddChild', parent={'path': 'application'}, tag='receiver',
                attrs={name: 'com.example.app.New', 'android:exported': 'true'},
                children=[{'tag': 'intent-filter',
                           'children': [{'tag': 'action', 'attrs': {name: 'com.example.IN'}}]}])
        m = self.get('AndroidManifest.xml')
        self.assertIn('<receiver android:name="com.example.app.New" android:exported="true">', m)
        self.assertIn('<action android:name="com.example.IN" />', m)
        self.assertLess(m.index('m.one'), m.index('com.example.app.New'))  # appended last
        with self.assertRaises(RuntimeError):
            self.op('manifestExpect', select={'path': '**', 'attr': {name: 'com.example.app.New'}},
                    expect=0)
        with self.assertRaises(RuntimeError):  # parent must match exactly once
            self.op('manifestAddChild', parent={'path': 'application/activity/nothing'}, tag='x')


if __name__ == '__main__':
    unittest.main()
