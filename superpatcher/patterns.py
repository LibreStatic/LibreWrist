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
SCHEMA = 2
_TOP_KEYS = ('schema', 'serial', 'minAppVersion', 'inputs', 'families', 'profiles', 'patterns',
             'outputs', 'installOrder', 'grants', 'stockAppsToDisable', 'apps', 'strings', 'platform')
_PLACEHOLDER = re.compile(r'\$\{([^}]*)\}')

# --- install section (outputs, installOrder, grants, stockAppsToDisable) -------------------
# A consumer turns these values into `adb shell` commands, so every string that can reach a
# command line is matched against a strict whitelist regex here.
_SAMPLE_PREFIX = 'com.example'     # ${PKG_PREFIX} stand-in used only to validate package names
_DEVICES = ('phone', 'watch')
_ID_RE = re.compile(r'^[a-z][a-z0-9-]*$', re.ASCII)
OPTION_GROUP_RE = re.compile(r'^[a-z][a-z0-9]*(-[a-z0-9]+)*$', re.ASCII)
_PACKAGE_RE = re.compile(r'^[a-zA-Z][\w]*(\.[a-zA-Z][\w]*)+$', re.ASCII)
_OWN_APP_RE = re.compile(r'^[a-z][a-z0-9-]*(/[a-z][a-z0-9-]*)*$', re.ASCII)
_PERMISSION_RE = re.compile(r'^[a-zA-Z][\w.]*$', re.ASCII)
_APPOP_RE = re.compile(r'^[A-Z][A-Z0-9_]*$', re.ASCII)
_SETTING_KEY_RE = re.compile(r'^[a-z][a-z0-9_]*$', re.ASCII)
_SETTING_VALUE_RE = re.compile(r'^\w[\w.-]{0,63}$', re.ASCII)   # no leading '-': it would be an option
_GLOBAL_SETTING_VALUE_RE = re.compile(r'^L[a-zA-Z0-9_/$-]{0,127}$', re.ASCII)
_COMPONENT_RE = re.compile(r'^(\.[A-Za-z]\w*)+$|^[A-Za-z]\w*(\.[A-Za-z]\w*)+$', re.ASCII)
_NOTE_RE = re.compile(r'^[^\x00-\x1f\x7f]{1,300}$', re.ASCII)
_APP_ICONS = ('monitor-heart', 'favorite', 'alarm', 'swap')   # closed vocabulary of apps[].icon
_OUTPUT_OPTIONAL_KEYS = ('start', 'companionLink', 'knownSigner', 'statusAuthority', 'singleCopy')
_LOCALE_RE = re.compile(r'^[a-z]{2,3}(-[A-Z][A-Za-z]{1,3})?$', re.ASCII)
_STRING_KEY_RE = re.compile(r'^[a-z][A-Za-z0-9_.-]{0,199}$', re.ASCII)
_TEXT_RE = re.compile(r'^[^\x00-\x1f\x7f]{1,4000}$')
_SETTINGS_COMPONENT_RE = re.compile(r'^[A-Za-z]\w*(\.[A-Za-z]\w*)+/(\.[A-Za-z]\w*)+$', re.ASCII)
_VIEW_ID_RE = re.compile(r'^[a-z]+:id/[a-z_][a-z0-9_]*$', re.ASCII)
_INSTALL_STEPS = ('userOnboarding',)   # values allowed in installOrder[].then
# Closed set of grant kinds: kind -> required fields (besides "kind"); "optional" (bool) and
# "note" (str) are accepted on every kind.
_GRANT_KINDS = {
    'permission': {'name': _PERMISSION_RE},
    'appop': {'op': _APPOP_RE, 'mode': re.compile(r'^allow$', re.ASCII)},
    'notificationListener': {'component': _COMPONENT_RE},
    'deviceIdleWhitelist': {},
    'secureSetting': {'key': _SETTING_KEY_RE, 'value': _SETTING_VALUE_RE},
    'globalSetting': {'key': _SETTING_KEY_RE, 'value': _GLOBAL_SETTING_VALUE_RE},
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
    unknown = set(g) - {'kind', 'optional', 'note', 'why'} - set(fields)
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
    if 'why' in g and not (isinstance(g['why'], str) and _STRING_KEY_RE.fullmatch(g['why'])):
        out.append(f'{where}: why must be a key of the strings table')
    return out


def _validate_output_extras(where, o):
    out = []
    if 'start' in o:
        st = o['start']
        if not isinstance(st, dict) or set(st) != {'receiver', 'action'}:
            out.append(f'{where}: start must be exactly {{"receiver", "action"}}')
        else:
            if not isinstance(st['receiver'], str) or not re.fullmatch(r'(\.[A-Za-z]\w*)+', st['receiver'],
                                                                      re.ASCII):
                out.append(f'{where}: start.receiver must be a class name relative to the package (".Name")')
            act = _sample_package(st['action'])
            if act is None or not _PACKAGE_RE.fullmatch(act):
                out.append(f'{where}: start.action is not a valid action name '
                           '(only ${PKG_PREFIX} may be used as a placeholder)')
    for k in ('companionLink', 'knownSigner', 'singleCopy'):
        if k in o and not isinstance(o[k], bool):
            out.append(f'{where}: {k} must be a boolean')
    if 'statusAuthority' in o:
        auth = _sample_package(o['statusAuthority'])
        if auth is None or not _PACKAGE_RE.fullmatch(auth):
            out.append(f'{where}: statusAuthority is not a valid authority '
                       '(only ${PKG_PREFIX} may be used as a placeholder)')
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
        base = {'id', 'device', 'package', 'from'}
        if not isinstance(o, dict) or not base <= set(o) or set(o) - base - set(_OUTPUT_OPTIONAL_KEYS):
            problems.append(f'{where}: expected id, device, package, from '
                            f'(plus optionally {", ".join(_OUTPUT_OPTIONAL_KEYS)})')
            continue
        problems.extend(_validate_output_extras(where, o))
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
            key = json.dumps({k: v for k, v in g.items() if k not in ('optional', 'note', 'why')},
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
        base = {'device', 'package', 'optional', 'reason'}
        if not isinstance(s, dict) or not base <= set(s) or set(s) - base - {'replacedBy'}:
            problems.append(f'{where}: expected device, package, optional, reason (plus optionally replacedBy)')
            continue
        if 'replacedBy' in s and s['replacedBy'] not in out_ids:
            problems.append(f'{where}: replacedBy {s["replacedBy"]!r} is not an output id')
        if s['device'] not in _DEVICES:
            problems.append(f'{where}: device must be one of {list(_DEVICES)}')
        if not isinstance(s['package'], str) or not _PACKAGE_RE.fullmatch(s['package']):
            problems.append(f'{where}: package is not a valid package name')
        if not isinstance(s['optional'], bool):
            problems.append(f'{where}: optional must be a boolean')
        if not (isinstance(s['reason'], str) and _NOTE_RE.fullmatch(s['reason'])):
            problems.append(f'{where}: reason must be a single-line string of 1..300 characters')
    problems.extend(_validate_apps(cat, out_ids))
    problems.extend(_validate_platform(cat, out_ids))
    problems.extend(_validate_strings(cat, out_ids))
    return problems


def _validate_apps(cat, out_ids):
    apps = cat['apps']
    if not isinstance(apps, list) or not apps:
        return ['apps must be a non-empty list']
    problems, ids, owner = [], [], {}
    for i, a in enumerate(apps):
        where = f'apps[{i}]'
        if not isinstance(a, dict) or set(a) != {'id', 'outputs', 'optional', 'icon'}:
            problems.append(f'{where}: expected exactly id, outputs, optional, icon')
            continue
        if not isinstance(a['id'], str) or not _ID_RE.fullmatch(a['id']):
            problems.append(f'{where}: id must match {_ID_RE.pattern}')
        elif a['id'] in ids:
            problems.append(f'{where}: duplicate id {a["id"]!r}')
        else:
            ids.append(a['id'])
        if not isinstance(a['optional'], bool):
            problems.append(f'{where}: optional must be a boolean')
        if a['icon'] not in _APP_ICONS:
            problems.append(f'{where}: icon must be one of {list(_APP_ICONS)}')
        outs = a['outputs']
        if not isinstance(outs, list) or not outs:
            problems.append(f'{where}: outputs must be a non-empty list')
            continue
        for oid in outs:
            if oid not in out_ids:
                problems.append(f'{where}: unknown output {oid!r}')
            elif oid in owner:
                problems.append(f'{where}: output {oid!r} is already in app {owner[oid]!r}')
            else:
                owner[oid] = a['id']
    for oid in out_ids:
        if oid not in owner:
            problems.append(f'apps: output {oid!r} is in no app')
    return problems


def _package_list(where, v):
    if not isinstance(v, list) or len(v) != len(set(map(str, v))) or \
            not all(isinstance(p, str) and _PACKAGE_RE.fullmatch(p) for p in v):
        return [f'{where}: must be a list of distinct package names']
    return []


def _validate_platform(cat, out_ids):
    p = cat['platform']
    if not isinstance(p, dict) or set(p) != {'companionApp', 'healthPlatform', 'logPackages', 'revertSettings'}:
        return ['platform: expected exactly companionApp, healthPlatform, logPackages, revertSettings']
    problems = []
    c = p['companionApp']
    if not isinstance(c, dict) or set(c) != {'package', 'watchPlugins'}:
        problems.append('platform.companionApp: expected exactly package, watchPlugins')
    else:
        if not isinstance(c['package'], str) or not _PACKAGE_RE.fullmatch(c['package']):
            problems.append('platform.companionApp.package is not a valid package name')
        problems.extend(_package_list('platform.companionApp.watchPlugins', c['watchPlugins']))
    h = p['healthPlatform']
    keys = {'package', 'settingsAction', 'settingsComponent', 'titleViewId', 'devModeViewId', 'taps',
            'requiredBy'}
    if not isinstance(h, dict) or set(h) != keys:
        problems.append(f'platform.healthPlatform: expected exactly {", ".join(sorted(keys))}')
    else:
        for k, rx in (('package', _PACKAGE_RE), ('settingsAction', _PACKAGE_RE),
                      ('settingsComponent', _SETTINGS_COMPONENT_RE), ('titleViewId', _VIEW_ID_RE),
                      ('devModeViewId', _VIEW_ID_RE)):
            if not isinstance(h[k], str) or not rx.fullmatch(h[k]):
                problems.append(f'platform.healthPlatform.{k} missing or not matching {rx.pattern}')
        if not (isinstance(h['taps'], int) and not isinstance(h['taps'], bool) and 1 <= h['taps'] <= 50):
            problems.append('platform.healthPlatform.taps must be an integer in 1..50')
        rb = h['requiredBy']
        if not isinstance(rb, list) or not all(o in out_ids for o in rb):
            problems.append('platform.healthPlatform.requiredBy must be a list of output ids')
    lp = p['logPackages']
    if not isinstance(lp, dict) or set(lp) != set(_DEVICES):
        problems.append('platform.logPackages: expected exactly phone, watch')
    else:
        for d in _DEVICES:
            problems.extend(_package_list(f'platform.logPackages.{d}', lp[d]))
    rs = p['revertSettings']
    if not isinstance(rs, list):
        problems.append('platform.revertSettings must be a list')
        rs = []
    for i, r in enumerate(rs):
        where = f'platform.revertSettings[{i}]'
        if not isinstance(r, dict) or not {'device', 'kind', 'key', 'default'} <= set(r) or \
                set(r) - {'device', 'kind', 'key', 'default', 'why'}:
            problems.append(f'{where}: expected device, kind, key, default (plus optionally why)')
            continue
        if r['device'] not in _DEVICES:
            problems.append(f'{where}: device must be one of {list(_DEVICES)}')
        if r['kind'] != 'secureSetting':
            problems.append(f'{where}: kind must be "secureSetting"')
        if not isinstance(r['key'], str) or not _SETTING_KEY_RE.fullmatch(r['key']):
            problems.append(f'{where}: key missing or not matching {_SETTING_KEY_RE.pattern}')
        if not isinstance(r['default'], str) or not _SETTING_VALUE_RE.fullmatch(r['default']):
            problems.append(f'{where}: default missing or not matching {_SETTING_VALUE_RE.pattern}')
        if 'why' in r and not (isinstance(r['why'], str) and _STRING_KEY_RE.fullmatch(r['why'])):
            problems.append(f'{where}: why must be a key of the strings table')
    return problems


def _validate_strings(cat, out_ids):
    st = cat['strings']
    if not isinstance(st, dict) or not isinstance(st.get('en'), dict) or not st['en']:
        return ['strings: must be an object with a non-empty "en" table']
    problems = []
    for loc, table in st.items():
        if not isinstance(loc, str) or not _LOCALE_RE.fullmatch(loc):
            problems.append(f'strings: locale {loc!r} is not a BCP-47 tag like "es" or "zh-TW"')
        if not isinstance(table, dict):
            problems.append(f'strings/{loc}: must be an object')
            continue
        for k, v in table.items():
            if not _STRING_KEY_RE.fullmatch(k):
                problems.append(f'strings/{loc}: key {k!r} must match {_STRING_KEY_RE.pattern}')
            elif loc != 'en' and k not in st['en']:
                problems.append(f'strings/{loc}: key {k!r} is not in strings/en')
            if not (isinstance(v, str) and _TEXT_RE.fullmatch(v)):
                problems.append(f'strings/{loc}/{k}: must be a single-line string of 1..4000 characters')
    en = st['en']
    app_ids = [a['id'] for a in cat['apps'] if isinstance(a, dict) and isinstance(a.get('id'), str)] \
        if isinstance(cat['apps'], list) else []
    required = [f'app.{a}.{f}' for a in app_ids for f in ('title', 'summary')] + \
        [f'output.{o}.label' for o in out_ids]
    for k in required:
        if k not in en:
            problems.append(f'strings/en: missing required key {k!r}')
    refs = []
    for oid, lst in cat['grants'].items() if isinstance(cat['grants'], dict) else ():
        for i, g in enumerate(lst if isinstance(lst, list) else ()):
            if isinstance(g, dict) and isinstance(g.get('why'), str):
                refs.append((f'grants/{oid}[{i}]', g['why']))
    rs = cat['platform'].get('revertSettings') if isinstance(cat['platform'], dict) else None
    for i, r in enumerate(rs if isinstance(rs, list) else ()):
        if isinstance(r, dict) and isinstance(r.get('why'), str):
            refs.append((f'platform.revertSettings[{i}]', r['why']))
    for where, key in refs:
        if key not in en:
            problems.append(f'{where}: why {key!r} is not in strings/en')
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
    groups = {}
    for pid, p in patterns.items():
        where = f'pattern {pid}'
        keys = {'families', 'title', 'description', 'optional', 'ops'}
        if not isinstance(p, dict) or not keys <= set(p) or set(p) - keys - {'optionGroup', 'offByDefault'}:
            problems.append(f'{where}: must have exactly the keys {sorted(keys)} '
                            '(plus optionally "optionGroup" and "offByDefault")')
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
        if 'offByDefault' in p:
            if not isinstance(p['offByDefault'], bool):
                problems.append(f'{where}: offByDefault must be a boolean')
            elif p['offByDefault'] and p['optional'] is not True:
                problems.append(f'{where}: offByDefault is only allowed on optional patterns')
        if 'optionGroup' in p:
            if not isinstance(p['optionGroup'], str) or not OPTION_GROUP_RE.match(p['optionGroup']):
                problems.append(f'{where}: optionGroup must be a lowercase kebab-case string')
            elif p['optional'] is not True:
                problems.append(f'{where}: optionGroup is only allowed on optional patterns')
            else:
                groups.setdefault(p['optionGroup'], []).append(pid)
        if not isinstance(p['ops'], list) or not p['ops']:
            problems.append(f'{where}: ops must be a non-empty list')
            continue
        for i, step in enumerate(p['ops']):
            try:
                _check_step(step)
            except (E.OpArgError, KeyError, TypeError) as e:
                problems.append(f'{where} step {i + 1}: {e}')
    for group, members in groups.items():
        if len(members) < 2:
            problems.append(f'optionGroup {group!r}: needs at least two patterns, has {members}')

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


def no_duplicate_keys(pairs):
    """json object_pairs_hook: reject duplicate keys, as the app's parser does."""
    obj = {}
    for k, v in pairs:
        if k in obj:
            raise CatalogueError([f'duplicate key {k!r}'])
        obj[k] = v
    return obj


def load_catalogue(path=None):
    """Load and validate the catalogue (default catalogue/catalogue.json). Raises
    CatalogueError listing every problem."""
    with open(path or DEFAULT_CATALOGUE, encoding='utf-8') as f:
        cat = json.load(f, object_pairs_hook=no_duplicate_keys)
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


def check_skip(patterns, skip):
    """Reject a skip set that names an unknown or a required pattern, or only part of an
    `optionGroup` (the members of a group are always on or off together)."""
    skip = set(skip)
    unknown = sorted(skip - set(patterns))
    if unknown:
        raise PatternRequiredError('cannot skip unknown pattern ids: ' + ', '.join(unknown))
    required = sorted(i for i in skip if not patterns[i]['optional'])
    if required:
        raise PatternRequiredError('cannot skip required patterns: ' + ', '.join(required))
    for group in {patterns[i].get('optionGroup') for i in skip} - {None}:
        members = {i for i, p in patterns.items() if p.get('optionGroup') == group}
        if not members <= skip:
            raise PatternRequiredError(
                f"option group '{group}' must be skipped as a whole: {', '.join(sorted(members))}")


def run_profile(tree, ids, dry=False, catalogue=None, skip=()):
    """Run the listed patterns IN ORDER. Returns [(id, description, log)]. Every listed pattern
    is required: an exception or an empty result aborts with PatternRequiredError (an
    `optional` pattern may log nothing, but still must not raise). `skip` names optional
    patterns to leave out (see check_skip); the ones that still run keep the same rules."""
    patterns = (catalogue or load_catalogue())['patterns']
    check_skip(patterns, skip)
    ids = [i for i in ids if i not in set(skip)]
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
