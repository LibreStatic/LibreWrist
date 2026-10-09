"""Consistency tests for apks/inputs.json and apks/SHA256SUMS (no APKs needed)."""
import copy
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import gen_sha256sums as g  # noqa: E402


class InputsManifestTest(unittest.TestCase):

    def setUp(self):
        self.manifest = g.load()

    def test_manifest_is_valid(self):
        self.assertEqual(g.validate(self.manifest), [])

    def test_sha256sums_matches_manifest(self):
        with open(g.SUMS) as f:
            self.assertEqual(f.read(), g.render(self.manifest),
                             'run scripts/gen_sha256sums.py')

    def test_validate_rejects_bad_entries(self):
        bad = copy.deepcopy(self.manifest)
        bad['inputs'][0]['sha256'] = 'XYZ'
        bad['inputs'][1]['target'] = 'tablet'
        bad['inputs'][2]['id'] = bad['inputs'][0]['id']
        del bad['inputs'][2]['package']
        errors = '\n'.join(g.validate(bad))
        for needle in ('sha256', 'target', 'duplicate id', 'missing package'):
            self.assertIn(needle, errors)


if __name__ == '__main__':
    unittest.main()
