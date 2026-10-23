"""Our code injected into vendor APKs must not hold a string constant that a catalogue
replaceConstStringExact op rewrites: the op checks afterwards that the old constant is gone from
every smali file, so such a literal fails the build on the device (#13, 0.10.4)."""
import glob
import json
import os
import re
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
INJECTED = ['watch-mod/src', 'shm-bridge/alarm-fork/src']
LITERAL = re.compile(r'"((?:[^"\\\n]|\\.)*)"')


def rewritten_constants():
    with open(os.path.join(ROOT, 'catalogue', 'catalogue.json'), encoding='utf-8') as f:
        catalogue = json.load(f)
    found = set()

    def walk(node):
        if isinstance(node, dict):
            if node.get('op') == 'replaceConstStringExact':
                found.add(node['old'])
            for v in node.values():
                walk(v)
        elif isinstance(node, list):
            for v in node:
                walk(v)

    walk(catalogue)
    return found


class InjectedConstants(unittest.TestCase):
    def test_catalogue_has_exact_string_rewrites(self):
        self.assertIn('com.samsung.android.shealthmonitor', rewritten_constants())

    def test_no_injected_literal_is_rewritten(self):
        olds = rewritten_constants()
        hits = []
        for d in INJECTED:
            for path in glob.glob(os.path.join(ROOT, d, '**', '*.java'), recursive=True):
                with open(path, encoding='utf-8') as f:
                    for n, line in enumerate(f, 1):
                        for lit in LITERAL.findall(line):
                            if lit in olds:
                                hits.append('%s:%d "%s"' % (os.path.relpath(path, ROOT), n, lit))
        self.assertEqual(hits, [], 'build the string at runtime or drop it')

    def test_detects_a_planted_literal(self):
        line = 'static final String P = "com.samsung.android.shealthmonitor";'
        self.assertIn('com.samsung.android.shealthmonitor', LITERAL.findall(line))


if __name__ == '__main__':
    unittest.main()
