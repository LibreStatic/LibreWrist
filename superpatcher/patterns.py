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
_TOP_KEYS = ('schema', 'serial', 'minAppVersion', 'inputs', 'families', 'profiles', 'patterns',
             'outputs', 'installOrder', 'grants', 'stockAppsToDisable')
_PLACEHOLDER = re.compile(r'\$\{([^}]*)\}')

# --- install section (outputs, installOrder, grants, stockAppsToDisable) -------------------
# A consumer turns these values into `adb shell` commands, so every string that can reach a
# command line is matched against a strict whitelist regex here.
_SAMPLE_PREFIX = 'com.example'     # ${PKG_PREFIX} stand-in used only to validate package names
_DEVICES = ('phone', 'watch')
_ID_RE = re.compile(r'^[a-z][a-z0-9-]*$', re.ASCII)
_PACKAGE_RE = re.compile(r'^[a-zA-Z][\w]*(\.[a-zA-Z][\w]*)+$', re.ASCII)
_OWN_APP_RE = re.compile(r'^[a-z][a-z0-9-]*(/[a-z][a-z0-9-]*)*$', re.ASCII)
_PERMISSION_RE = re.compile(r'^[a-zA-Z][\w.]*$', re.ASCII)
_APPOP_RE = re.compile(r'^[A-Z][A-Z0-9_]*$', re.ASCII)
_SETTING_KEY_RE = re.compile(r'^[a-z][a-z0-9_]*$', re.ASCII)
_SETTING_VALUE_RE = re.compile(r'^\w[\w.-]{0,63}$', re.ASCII)   # no leading '-': it would be an option
_COMPONENT_RE = re.compile(r'^(\.[A-Za-z]\w*)+$|^[A-Za-z]\w*(\.[A-Za-z]\w*)+$', re.ASCII)
_NOTE_RE = re.compile(r'^[^\x00-\x1f\x7f]{1,300}$', re.ASCII)
_INSTALL_STEPS = ('userOnboarding',)   # values allowed in installOrder[].then
# Closed set of grant kinds: kind -> required fields (besides "kind"); "optional" (bool) and
# "note" (str) are accepted on every kind.
_GRANT_KINDS = {
    'permission': {'name': _PERMISSION_RE},
    'appop': {'op': _APPOP_RE, 'mode': re.compile(r'^allow$', re.ASCII)},
    'notificationListener': {'component': _COMPONENT_RE},
    'deviceIdleWhitelist': {},
    'secureSetting': {'key': _SETTING_KEY_RE, 'value': _SETTING_VALUE_RE},
}


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


def _sample_package(value):
    """Expand ${PKG_PREFIX} with a sample prefix; None if value is not a string or has any
    other placeholder."""
    if not isinstance(value, str):
        return None
    out = value.replace('${PKG_PREFIX}', _SAMPLE_PREFIX)
    return None if '$' in out or '{' in out else out


def _profile_families(profile):
    fams = profile.get('families') if isinstance(profile, dict) else None
    return fams if isinstance(fams, dict) else {}


def _validate_grant(where, g):
    if not isinstance(g, dict) or not isinstance(g.get('kind'), str) or \
            g['kind'] not in _GRANT_KINDS:
        return [f'{where}: "kind" must be one of {sorted(_GRANT_KINDS)}']
    fields = _GRANT_KINDS[g['kind']]
    out = []
    unknown = set(g) - {'kind', 'optional', 'note'} - set(fields)
    if unknown:
        out.append(f'{where}: unknown fields {sorted(unknown)} for kind {g["kind"]!r}')
    for name, rx in fields.items():
        v = g.get(name)
        if not isinstance(v, str) or not rx.fullmatch(v):
            out.append(f'{where}: field {name!r} missing or not matching {rx.pattern}')
    if 'optional' in g and not isinstance(g['optional'], bool):
        out.append(f'{where}: optional must be a boolean')
    if 'note' in g and not (isinstance(g['note'], str) and _NOTE_RE.fullmatch(g['note'])):
        out.append(f'{where}: note must be a single-line string of 1..300 characters')
    return out


def _validate_install(cat, input_targets, profiles):
    """Problems of outputs / installOrder / grants / stockAppsToDisable. `input_targets` maps
    input id -> device, `profiles` is the (dict) profiles section."""
    problems = []
    outputs = cat['outputs']
    out_ids = []
    seen_pkgs = set()
    if not isinstance(outputs, list) or not outputs:
        problems.append('outputs must be a non-empty list')
        outputs = []
    for i, o in enumerate(outputs):
        where = f'outputs[{i}]'
        if not isinstance(o, dict) or set(o) != {'id', 'device', 'package', 'from'}:
            problems.append(f'{where}: expected exactly id, device, package, from')
            continue
        if not isinstance(o['id'], str) or not _ID_RE.fullmatch(o['id']):
            problems.append(f'{where}: id must match {_ID_RE.pattern}')
        elif o['id'] in out_ids:
            problems.append(f'{where}: duplicate id {o["id"]!r}')
        else:
            out_ids.append(o['id'])
        if o['device'] not in _DEVICES:
            problems.append(f'{where}: device must be one of {list(_DEVICES)}')
        pkg = _sample_package(o['package'])
        if pkg is None or not _PACKAGE_RE.fullmatch(pkg):
            problems.append(f'{where}: package {o["package"]!r} is not a valid package name '
                            '(only ${PKG_PREFIX} may be used as a placeholder)')
        elif (str(o['device']), pkg) in seen_pkgs:
            problems.append(f'{where}: package {o["package"]!r} repeated on device {o["device"]}')
        else:
            seen_pkgs.add((str(o['device']), pkg))
        src = o['from']
        if isinstance(src, dict) and set(src) == {'input', 'profile'}:
            if not isinstance(src['input'], str) or not isinstance(src['profile'], str):
                problems.append(f'{where}: from.input and from.profile must be strings')
            else:
                if src['input'] not in input_targets:
                    problems.append(f'{where}: from.input {src["input"]!r} is not in inputs')
                elif input_targets[src['input']] != o['device']:
                    problems.append(f'{where}: from.input {src["input"]!r} targets '
                                    f'{input_targets[src["input"]]!r}, not {o["device"]!r}')
                if src['profile'] not in profiles:
                    problems.append(f'{where}: from.profile {src["profile"]!r} is not in profiles')
                elif src['input'] not in _profile_families(profiles[src['profile']]):
                    problems.append(f'{where}: profile {src["profile"]!r} has no patterns for '
                                    f'{src["input"]!r}')
        elif isinstance(src, dict) and set(src) == {'ownApp'}:
            a = src['ownApp']
            if not isinstance(a, str) or not _OWN_APP_RE.fullmatch(a):
                problems.append(f'{where}: from.ownApp must match {_OWN_APP_RE.pattern}')
        else:
            problems.append(f'{where}: from must be {{"input", "profile"}} or {{"ownApp"}}')

    order = cat['installOrder']
    if not isinstance(order, list):
        problems.append('installOrder must be a list')
        order = []
    ordered = []
    for i, step in enumerate(order):
        where = f'installOrder[{i}]'
        if not isinstance(step, dict) or not set(step) <= {'output', 'then'} or 'output' not in step:
            problems.append(f'{where}: expected {{"output", "then"?}}')
            continue
        if step['output'] not in out_ids:
            problems.append(f'{where}: unknown output {step["output"]!r}')
        elif step['output'] in ordered:
            problems.append(f'{where}: output {step["output"]!r} listed twice')
        else:
            ordered.append(step['output'])
        if 'then' in step and step['then'] not in _INSTALL_STEPS:
            problems.append(f'{where}: then must be one of {list(_INSTALL_STEPS)}')
    for oid in out_ids:
        if oid not in ordered:
            problems.append(f'installOrder: output {oid!r} is missing')

    grants = cat['grants']
    if not isinstance(grants, dict):
        problems.append('grants must be an object')
        grants = {}
    for oid, lst in grants.items():
        where = f'grants/{oid}'
        if oid not in out_ids:
            problems.append(f'{where}: not an output id')
        if not isinstance(lst, list):
            problems.append(f'{where}: must be a list')
            continue
        seen = []
        for i, g in enumerate(lst):
            problems.extend(_validate_grant(f'{where}[{i}]', g))
            key = json.dumps({k: v for k, v in g.items() if k not in ('optional', 'note')},
                             sort_keys=True) if isinstance(g, dict) else None
            if key is not None and key in seen:
                problems.append(f'{where}[{i}]: duplicate grant')
            seen.append(key)

    stock = cat['stockAppsToDisable']
    if not isinstance(stock, list):
        problems.append('stockAppsToDisable must be a list')
        stock = []
    for i, s in enumerate(stock):
        where = f'stockAppsToDisable[{i}]'
        if not isinstance(s, dict) or set(s) != {'device', 'package', 'optional', 'reason'}:
            problems.append(f'{where}: expected exactly device, package, optional, reason')
            continue
        if s['device'] not in _DEVICES:
            problems.append(f'{where}: device must be one of {list(_DEVICES)}')
        if not isinstance(s['package'], str) or not _PACKAGE_RE.fullmatch(s['package']):
            problems.append(f'{where}: package is not a valid package name')
        if not isinstance(s['optional'], bool):
            problems.append(f'{where}: optional must be a boolean')
        if not (isinstance(s['reason'], str) and _NOTE_RE.fullmatch(s['reason'])):
            problems.append(f'{where}: reason must be a single-line string of 1..300 characters')
    return problems


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
    if isinstance(cat['inputs'], list):
        targets = {i['id']: i.get('target') for i in cat['inputs']
                   if isinstance(i, dict) and isinstance(i.get('id'), str)}
        problems.extend(_validate_install(cat, targets, profiles))
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
