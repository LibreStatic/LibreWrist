"""Device-free tests for the class-targeted engine ops and the alarm-fork patterns that do
not need the vendor APK (synthetic trees; temp data under WORK_DIR)."""
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

CAT = P.load_catalogue()
ALARM_PKG = 'com.samsung.android.watch.alarm'
ALARM_MAIN_ACTIVITY = ALARM_PKG + '.activity.AlarmMainActivity'
ALARM_LABEL_NAME = 'interop_app_label'


def run_pattern(tree, pid):
    return P.run_pattern(tree, CAT['patterns'][pid])

ANDROID = 'xmlns:android="http://schemas.android.com/apk/res/android"'
MANIFEST = f'''<?xml version="1.0" encoding="utf-8"?>
<manifest {ANDROID} package="{ALARM_PKG}">
    <application android:label="@string/app_name">
        <activity android:label="@string/app_name" android:name="{ALARM_MAIN_ACTIVITY}">
            <intent-filter>
                <action android:name="android.intent.action.MAIN"/>
                <category android:name="android.intent.category.LAUNCHER"/>
            </intent-filter>
            <intent-filter>
                <action android:name="android.intent.action.VIEW"/>
                <category android:name="android.intent.category.DEFAULT"/>
            </intent-filter>
        </activity>
    </application>
</manifest>
'''
SMALI = '''.class public Lw2/k;
.super Ljava/lang/Object;

.method public onDestroy()V
    .locals 2

    const-string v0, "old"

    return-void
.end method

.method public static e(I)V
    .locals 1

    return-void
.end method
'''


class Tree(unittest.TestCase):
    def setUp(self):
        os.makedirs(config.CFG['WORK_DIR'], exist_ok=True)
        self.tree = tempfile.mkdtemp(prefix='test-alarm-', dir=config.CFG['WORK_DIR'])
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


class ClassOps(Tree):
    def setUp(self):
        super().setUp()
        self.put('smali_classes2/w2/k.smali', SMALI)

    def test_replace_in_class_exact_count(self):
        E.op_replace_in_class(self.tree, 'w2/k', '"old"', '"new"')
        self.assertIn('"new"', self.get('smali_classes2/w2/k.smali'))

    def test_replace_in_class_wrong_count_fails(self):
        with self.assertRaises(RuntimeError):
            E.op_replace_in_class(self.tree, 'w2/k', '"old"', '"new"', count=2)
        with self.assertRaises(RuntimeError):
            E.op_replace_in_class(self.tree, 'w2/k', '"missing"', '"new"')

    def test_missing_class_fails(self):
        with self.assertRaises(RuntimeError):
            E.op_replace_in_class(self.tree, 'w2/zz', 'a', 'b')

    def test_insert_after_locals(self):
        E.op_insert_in_class_method(self.tree, 'w2/k', 'public onDestroy()V', '\n    nop\n')
        self.assertIn('.locals 2\n\n    nop\n\n    const-string', self.get('smali_classes2/w2/k.smali'))

    def test_insert_unknown_method_fails(self):
        with self.assertRaises(RuntimeError):
            E.op_insert_in_class_method(self.tree, 'w2/k', 'public nope()V', 'x')

    def test_replace_method_body_and_guard(self):
        E.op_replace_class_method(self.tree, 'w2/k', 'public static e(I)V',
                                  '    .locals 0\n    return-void')
        src = self.get('smali_classes2/w2/k.smali')
        self.assertIn('.method public static e(I)V\n    .locals 0\n    return-void\n.end method', src)
        self.assertIn('const-string v0, "old"', src)  # other method untouched
        with self.assertRaises(RuntimeError):
            E.op_replace_class_method(self.tree, 'w2/k', 'public onDestroy()V', 'x',
                                      must_contain=('not-in-body',))

    def test_remove_smali_dir(self):
        self.put('smali/dropme/A.smali', '.class LA;')
        E.op_remove_smali_dir(self.tree, 'dropme')
        self.assertFalse(os.path.exists(os.path.join(self.tree, 'smali', 'dropme')))
        with self.assertRaises(RuntimeError):
            E.op_remove_smali_dir(self.tree, 'dropme')


class ManifestPatterns(Tree):
    def setUp(self):
        super().setUp()
        self.put('AndroidManifest.xml', MANIFEST)

    def test_hide_launcher_keeps_activity_and_other_filters(self):
        run_pattern(self.tree, 'alarm-hide-launcher')
        m = self.get('AndroidManifest.xml')
        self.assertNotIn('LAUNCHER', m)
        self.assertNotIn('android.intent.action.MAIN', m)
        self.assertIn('AlarmMainActivity', m)
        self.assertIn('android.intent.action.VIEW', m)

    def test_hide_launcher_fails_when_anchor_missing(self):
        run_pattern(self.tree, 'alarm-hide-launcher')
        with self.assertRaises(RuntimeError):
            run_pattern(self.tree, 'alarm-hide-launcher')

    def test_relabel(self):
        self.put('res/values/strings.xml', '<resources>\n</resources>\n')
        self.put('res/values-es/strings.xml', '<resources>\n</resources>\n')
        run_pattern(self.tree, 'alarm-relabel')
        m = self.get('AndroidManifest.xml')
        self.assertEqual(m.count('@string/interop_app_label'), 2)
        self.assertNotIn('@string/app_name', m)
        self.assertIn('>Phone alarms<', self.get('res/values/strings.xml'))
        self.assertIn('>Alarmas del teléfono<', self.get('res/values-es/strings.xml'))

    def test_relabel_name_collision_fails(self):
        self.put('res/values/strings.xml',
                 f'<resources>\n<string name="{ALARM_LABEL_NAME}">x</string>\n</resources>\n')
        self.put('res/values-es/strings.xml', '<resources>\n</resources>\n')
        with self.assertRaises(RuntimeError):
            run_pattern(self.tree, 'alarm-relabel')

    def test_family_detection(self):
        self.assertEqual(P.detect_family(self.tree), 'alarm-watch')

    def test_pinned_patterns_fail_on_foreign_tree(self):
        for pid in ('alarm-rebrand-code', 'alarm-semgesture-skip', 'alarm-link-send',
                    'alarm-components-prune', 'alarm-permissions-prune'):
            with self.assertRaises(RuntimeError, msg=pid):
                run_pattern(self.tree, pid)


class Profile(unittest.TestCase):
    def test_profile_ids_exist_and_order(self):
        ids = CAT['profiles']['alarm']['families']['alarm-watch']
        self.assertTrue(all(i in CAT['patterns'] for i in ids))
        self.assertEqual(ids[-2:], ['alarm-hide-launcher', 'alarm-relabel'])
        self.assertEqual(len(ids), len(set(ids)))


if __name__ == '__main__':
    unittest.main()
