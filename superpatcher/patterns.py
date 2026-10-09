"""Catalogue loader and interpreter of the super-patcher.

The patches are DATA (catalogue/catalogue.json, see docs/patches.md): this module loads and
validates the catalogue, detects the family of a decoded APK, and runs a profile by
interpreting each pattern's steps with the named ops of superpatcher/engine.py. It contains no
pattern-specific code.
"""
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, 'scripts', 'lib'))
import config  # noqa: E402

from superpatcher import engine as E  # noqa: E402

DEFAULT_CATALOGUE = os.path.join(ROOT, 'catalogue', 'catalogue.json')
SCHEMA = 1
_TOP_KEYS = ('schema', 'serial', 'minAppVersion', 'inputs', 'families', 'profiles', 'patterns')
_PLACEHOLDER = re.compile(r'\$\{([^}]*)\}')


class CatalogueError(ValueError):
    """The catalogue is malformed (.problems lists every problem found)."""
    def __init__(self, problems):
        super().__init__('invalid catalogue:\n  ' + '\n  '.join(problems))
        self.problems = problems


def placeholders():
    """The only templating: ${PKG_PREFIX} (e.g. com.example) and ${PKG_PATH} (com/example)."""
    return {'PKG_PREFIX': config.PKG_PREFIX, 'PKG_PATH': config.PKG_PATH}


def expand(value):
    """Expand the placeholders in every string VALUE of a (nested) JSON value; an unknown
    ${...} is an error."""
    if isinstance(value, str):
        known = placeholders()

        def sub(m):
            if m.group(1) not in known:
                raise E.OpArgError(f'unknown placeholder ${{{m.group(1)}}}')
            return known[m.group(1)]
        return _PLACEHOLDER.sub(sub, value)
    if isinstance(value, list):
        return [expand(v) for v in value]
    if isinstance(value, dict):
        return {k: expand(v) for k, v in value.items()}
    return value


def _check_step(step):
    """Validate one step (expansion + op/argument check); returns (name, func, kwargs)."""
    if not isinstance(step, dict) or not isinstance(step.get('op'), str):
        raise E.OpArgError('a step must be an object with an "op" name')
    step = expand(step)
    name = step['op']
    func, kwargs = E.prepare_op(name, {k: v for k, v in step.items() if k != 'op'})
    return name, func, kwargs


def validate_catalogue(cat):
    """List of problems of the catalogue structure, profiles/patterns cross references and
    every op step (name, arguments, types). Empty list = valid. Does not run anything."""
    problems = []
    if not isinstance(cat, dict):
        return ['top level must be an object']
    for k in _TOP_KEYS:
        if k not in cat:
            problems.append(f'missing top-level key {k!r}')
    for k in cat:
        if k not in _TOP_KEYS:
            problems.append(f'unknown top-level key {k!r}')
    if problems:
        return problems
    if cat['schema'] != SCHEMA:
        problems.append(f'schema is {cat["schema"]!r}, this engine understands {SCHEMA}')
    for k in ('serial', 'minAppVersion'):
        if not (isinstance(cat[k], int) and not isinstance(cat[k], bool) and cat[k] >= 1):
            problems.append(f'{k} must be an integer >= 1')
    if not isinstance(cat['inputs'], list):
        problems.append('inputs must be a list')

    fam_ids = []
    if not isinstance(cat['families'], list) or not cat['families']:
        problems.append('families must be a non-empty list')
    else:
        for i, rule in enumerate(cat['families']):
            if not isinstance(rule, dict) or not isinstance(rule.get('id'), str) or \
                    not isinstance(rule.get('package'), str) or \
                    set(rule) - {'id', 'package', 'markerClass'} or \
                    not isinstance(rule.get('markerClass', ''), str):
                problems.append(f'families[{i}]: expected {{"id", "package", "markerClass"?}}')
                continue
            if rule['id'] in fam_ids:
                problems.append(f'families[{i}]: duplicate id {rule["id"]!r}')
            fam_ids.append(rule['id'])

    patterns = cat['patterns'] if isinstance(cat['patterns'], dict) else {}
    if not isinstance(cat['patterns'], dict):
        problems.append('patterns must be an object')
    for pid, p in patterns.items():
        where = f'pattern {pid}'
        keys = {'families', 'title', 'description', 'optional', 'ops'}
        if not isinstance(p, dict) or set(p) != keys:
            problems.append(f'{where}: must have exactly the keys {sorted(keys)}')
            continue
        fams = p['families']
        if fams != '*' and not (isinstance(fams, list) and fams and
                                all(f in fam_ids for f in fams)):
            problems.append(f'{where}: families must be "*" or a list of known family ids')
        for k in ('title', 'description'):
            if not isinstance(p[k], str) or not p[k]:
                problems.append(f'{where}: {k} must be a non-empty string')
        if not isinstance(p['optional'], bool):
            problems.append(f'{where}: optional must be a boolean')
        if not isinstance(p['ops'], list) or not p['ops']:
            problems.append(f'{where}: ops must be a non-empty list')
            continue
        for i, step in enumerate(p['ops']):
            try:
                _check_step(step)
            except (E.OpArgError, KeyError, TypeError) as e:
                problems.append(f'{where} step {i + 1}: {e}')

    used = set()
    profiles = cat['profiles'] if isinstance(cat['profiles'], dict) else {}
    if not isinstance(cat['profiles'], dict):
        problems.append('profiles must be an object')
    for name, prof in profiles.items():
        where = f'profile {name}'
        if not isinstance(prof, dict) or set(prof) != {'description', 'families', 'inject'}:
            problems.append(f'{where}: must have exactly description, families, inject')
            continue
        for fam, ids in prof['families'].items():
            if fam not in fam_ids:
                problems.append(f'{where}: unknown family {fam!r}')
            if not isinstance(ids, list) or len(ids) != len(set(ids)):
                problems.append(f'{where}/{fam}: must be a list without duplicates')
                continue
            for pid in ids:
                used.add(pid)
                if pid not in patterns:
                    problems.append(f'{where}/{fam}: unknown pattern {pid!r}')
                elif isinstance(patterns[pid], dict) and patterns[pid].get('families') != '*' \
                        and fam not in (patterns[pid].get('families') or ()):
                    problems.append(f'{where}/{fam}: pattern {pid!r} is not valid for {fam}')
        for fam, names in prof['inject'].items():
            if fam not in prof['families']:
                problems.append(f'{where}: inject for family {fam!r} without patterns')
            if not isinstance(names, list) or not all(isinstance(n, str) for n in names):
                problems.append(f'{where}/inject/{fam}: must be a list of names')
    for pid in patterns:
        if pid not in used:
            problems.append(f'pattern {pid}: not used by any profile')
    return problems


def load_catalogue(path=None):
    """Load and validate the catalogue (default catalogue/catalogue.json). Raises
    CatalogueError listing every problem."""
    with open(path or DEFAULT_CATALOGUE, encoding='utf-8') as f:
        cat = json.load(f)
    problems = validate_catalogue(cat)
    if problems:
        raise CatalogueError(problems)
    return cat


def get_profile(cat, name):
    """The profile `name` as {'name', 'description', 'families', 'inject'}."""
    if name not in cat['profiles']:
        raise KeyError(f'unknown profile {name!r} (available: {", ".join(sorted(cat["profiles"]))})')
    return dict(cat['profiles'][name], name=name)


def detect_family(tree, catalogue=None):
    """Family id of a decoded tree: the first catalogue rule whose `package` equals the
    manifest package (first `package="..."` of AndroidManifest.xml) and, if the rule has a
    `markerClass`, whose class exists in any smali dir. 'unknown:<package>' if none."""
    cat = catalogue or load_catalogue()
    manifest = E.read(os.path.join(tree, 'AndroidManifest.xml'))
    pkg_m = re.search(r'package="([^"]+)"', manifest)
    pkg = pkg_m.group(1) if pkg_m else ''
    for rule in cat['families']:
        if rule['package'] != pkg:
            continue
        marker = rule.get('markerClass')
        if marker is None or E.class_smali_path(tree, marker):
            return rule['id']
    return 'unknown:' + pkg


def run_pattern(tree, pattern):
    """Interpret the steps of one catalogue pattern in order; returns the combined log.
    Any step failure raises RuntimeError naming the step."""
    log = []
    for i, step in enumerate(pattern['ops']):
        name = step.get('op', '?') if isinstance(step, dict) else '?'
        try:
            name, func, kwargs = _check_step(step)
            log.extend(func(tree, **kwargs))
        except Exception as e:  # noqa: BLE001
            raise RuntimeError(f'step {i + 1} ({name}): {e}') from e
    return log


class PatternRequiredError(RuntimeError):
    """A listed pattern did not match (or raised): the build must abort rather than sign an
    APK that is missing a patch. .report carries the partial results for diagnosis."""
    def __init__(self, message, report=None):
        super().__init__(message)
        self.report = report or []


def run_profile(tree, ids, dry=False, catalogue=None):
    """Run the listed patterns IN ORDER. Returns [(id, description, log)]. Every listed pattern
    is required: an exception or an empty result aborts with PatternRequiredError (an
    `optional` pattern may log nothing, but still must not raise)."""
    patterns = (catalogue or load_catalogue())['patterns']
    unknown = [i for i in ids if i not in patterns]
    if unknown:
        raise PatternRequiredError('unknown pattern ids in profile: ' + ', '.join(unknown))
    report, fatal = [], []
    for pid in ids:
        p = patterns[pid]
        log = []
        if not dry:
            try:
                log.extend(run_pattern(tree, p))
            except Exception as e:  # noqa: BLE001
                log.append(f'EXCEPTION: {e}')
            errs = [x for x in log if x.startswith('EXCEPTION')]
            if errs:
                fatal.append(f"pattern '{pid}' raised: {errs[0]}")
            elif not log and not p['optional']:
                fatal.append(f"pattern '{pid}' matched nothing (did the base change? check the anchors)")
        report.append((pid, p['description'], log))
        if fatal:
            break
    if fatal:
        raise PatternRequiredError('BUILD FAILED - ' + '; '.join(fatal), report)
    return report
