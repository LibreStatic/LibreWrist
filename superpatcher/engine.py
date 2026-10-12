"""Super-patcher engine: the named operations the patch catalogue (catalogue/catalogue.json)
is made of.

Contract (the Kotlin engine on Android must implement the same one):

* The catalogue is DATA. A pattern is a list of steps ``{"op": "<name>", ...args}``. ``OPS``
  below is the explicit registry name -> function; there is no dynamic dispatch. An unknown op,
  an unknown argument, a missing required argument or an argument of the wrong type is a hard
  error (``OpArgError``).
* JSON argument names are camelCase; the Python parameter is the same name in snake_case
  (``fileRegex`` -> ``file_regex``). The annotation of each parameter is its type tag:
  ``str``, ``text``, ``int``, ``bool``, ``strs`` (list of str), ``strmap`` (str -> str),
  ``expect``, ``mexpect``, ``select``, ``checks``, ``children``, ``enumfields``.
* ``text``: a string, or a list of strings joined with "\\n" (no trailing newline is added; end
  the list with "" for one). Used for smali snippets: one array element per line.
* String arguments may contain ``${PKG_PREFIX}`` / ``${PKG_PATH}`` (expanded by the interpreter,
  superpatcher/patterns.py, before the op runs). Nothing else is templated, except the
  ``{{name}}`` captures of ``insertInMethodAtMatch``.
* Every op takes the decoded apktool tree and returns the list of touched sites (log lines).
  Ops raise ``RuntimeError`` when an anchor is missing, ambiguous or has the wrong count.
  ``expect`` (site-count assertion): an exact integer, ``"atLeastOne"`` (default) or ``"any"``
  (no assertion; zero sites is allowed). A pattern whose steps log nothing at all fails.
* Regexes are Python ``re`` syntax restricted to the portable subset (classes, groups,
  alternation, lazy/greedy quantifiers, named groups ``(?P<n>...)`` / ``(?P=n)``,
  ``\\w \\d \\s \\b``).
* Paths in ``fileRegex`` are matched with ``re.search`` against the path relative to the tree,
  using "/" separators (e.g. ``smali_classes2/com/x/Y.smali``).

Method selection (``insertAtMethodStart``, ``returnSingleton``, ``setMethodBody``,
``insertInMethodAtMatch``) shares one selector, all parts ANDed:
``fileRegex`` (search on relative path), ``fileAll`` (file text contains every string),
``fileAny`` (contains at least one), ``sigRegex`` (fullmatch against the ``.method`` line
without the ``.method `` prefix), ``sigExclude`` (signature contains none of the strings),
``bodyAll`` / ``bodyNone`` (the body, i.e. the lines after the header up to ``.end method``,
contains all / none of the strings).

See docs/patches.md.
"""
import os
import re


class OpArgError(ValueError):
    """Unknown op, or an unknown / missing / ill-typed argument."""


def read(path):
    with open(path, errors='replace') as f:
        return f.read()


def write(path, s):
    with open(path, 'w', errors='replace') as f:
        f.write(s)


def smali_files(tree):
    for dirpath, _, fnames in os.walk(tree):
        for fn in fnames:
            if fn.endswith('.smali'):
                yield os.path.join(dirpath, fn)


def _rel(tree, path):
    return os.path.relpath(path, tree).replace(os.sep, '/')


def method_span(src, sig_regex):
    """Span (start, end) of the first method whose header matches sig_regex."""
    m = re.search(r'\.method\s+' + sig_regex + r'\n', src)
    if not m:
        return None
    end = src.find('\n.end method', m.start())
    return (m.start(), end + len('\n.end method'))


def method_body(src, sig_regex):
    span = method_span(src, sig_regex)
    if not span:
        return None
    return src[span[0]:span[1]]


def bump_locals(body, need=1):
    m = re.search(r'\.locals (\d+)', body)
    if not m:
        return body
    n = int(m.group(1))
    if n < need:
        body = body.replace(f'.locals {n}', f'.locals {need}', 1)
    return body


def insert_at_method_start(src, sig_regex, insert, need_regs=1, expect=1):
    """Insert `insert` after .locals of the first `expect` matching methods.
    Returns (new_src, ok). Capture groups from the caller are neutralised
    (group(3) is always the .locals count)."""
    sig_regex = re.sub(r'(?<!\\)\((?!\?)', '(?:', sig_regex)
    pat = re.compile(r'(\.method\s+' + sig_regex + r'\n)(\s*\.locals (\d+)\n)')
    state = {'count': 0}
    out = []
    last = 0
    for m in pat.finditer(src):
        out.append(src[last:m.start()])
        body_after = src[m.end():m.end() + len(insert)]
        if state['count'] >= expect or body_after == insert:
            # already applied (idempotency) or quota reached
            state['count'] += 1
            out.append(src[m.start():m.end()])
            last = m.end()
            continue
        state['count'] += 1
        locals_n = max(int(m.group(3)), need_regs)
        out.append(m.group(1) + f'    .locals {locals_n}\n' + insert)
        last = m.end()
    out.append(src[last:])
    return ''.join(out), state['count'] >= expect


def iter_methods(src):
    """(signature, body) of every method in a smali file."""
    for m in re.finditer(r'\.method\s+([^\n]+)\n(.*?)(?=\n\.end method)', src, re.S):
        yield m.group(1), m.group(2)


def _check_expect(label, found, expect):
    """Raise unless `found` (a site count) satisfies `expect` (int | atLeastOne | any)."""
    if expect == 'any':
        return
    if expect == 'atLeastOne':
        if found < 1:
            raise RuntimeError(f'{label}: matched nothing (did the base change? '
                               'check the anchors)')
        return
    if found != expect:
        raise RuntimeError(f'{label}: expected {expect} site(s) and found {found} '
                           '(did the base change? check the anchors)')


def _select_methods(tree, file_regex, file_all, file_any, sig_regex, sig_exclude, body_all,
                    body_none):
    """[(path, src, [signature, ...])] for the files with at least one selected method
    (see the module docstring for the selector)."""
    out = []
    for f in smali_files(tree):
        if file_regex and not re.search(file_regex, _rel(tree, f)):
            continue
        src = read(f)
        if any(a not in src for a in file_all or ()):
            continue
        if file_any and not any(a in src for a in file_any):
            continue
        sigs = []
        for sig, body in iter_methods(src):
            if not re.fullmatch(sig_regex, sig):
                continue
            if any(x in sig for x in sig_exclude or ()):
                continue
            if any(a not in body for a in body_all or ()):
                continue
            if any(a in body for a in body_none or ()):
                continue
            sigs.append(sig)
        if sigs:
            out.append((f, src, sigs))
    return out


# ---------------------------------------------------------------- method ops

def op_insert_at_method_start(tree, sig_regex: 'str', insert: 'text',
                              file_regex: 'str' = None, file_all: 'strs' = None,
                              file_any: 'strs' = None, sig_exclude: 'strs' = None,
                              body_all: 'strs' = None, body_none: 'strs' = None,
                              need_regs: 'int' = 1, tail: 'text' = None,
                              expect: 'expect' = 'atLeastOne'):
    """Insert `insert` right after the `.locals` line of every selected method (selector in
    the module docstring), raising `.locals` to at least `needRegs`. A selected method whose
    `.locals` line does not directly follow the header is not patched (and not counted).
    If `tail` is given it is also inserted before the LAST `    return ` line of the method
    (skipped if the method body already contains it). Idempotent: a method that already starts
    with `insert` is left alone but still counted. `expect` is checked against the number of
    patched methods."""
    done = []
    for f, src, sigs in _select_methods(tree, file_regex, file_all, file_any, sig_regex,
                                        sig_exclude, body_all, body_none):
        orig = src
        for sig in sigs:
            src2, ok = insert_at_method_start(src, re.escape(sig), insert, need_regs=need_regs)
            if not ok:
                continue
            if tail:
                span = method_span(src2, re.escape(sig))
                body = src2[span[0]:span[1]]
                if tail not in body:
                    idx = max(body.rfind('    return '), 0)
                    body = body[:idx] + tail + body[idx:]
                    src2 = src2[:span[0]] + body + src2[span[1]:]
            src = src2
            done.append(_rel(tree, f) + ' :: ' + sig[:60])
        if src != orig:
            write(f, src)
    _check_expect('insertAtMethodStart ' + sig_regex[:50], len(done), expect)
    return done


def op_return_singleton(tree, sig_regex: 'str', field: 'str', file_regex: 'str' = None,
                        file_all: 'strs' = None, file_any: 'strs' = None,
                        sig_exclude: 'strs' = None, body_all: 'strs' = None,
                        body_none: 'strs' = None, expect: 'expect' = 'atLeastOne'):
    """Like insertAtMethodStart, with the insert
    `sget-object v0, <R>-><field>:<R>` + `return-object v0` (a blank line before each, i.e.
    "\\n    sget-object ...\\n\\n    return-object v0\\n"), where <R> is the object type
    the selected method returns (`)L...;` at the end of its signature; a selected method with
    another return type is an error). needRegs is 1."""
    done = []
    for f, src, sigs in _select_methods(tree, file_regex, file_all, file_any, sig_regex,
                                        sig_exclude, body_all, body_none):
        orig = src
        for sig in sigs:
            m = re.search(r'\)(L[^;]+;)$', sig)
            if not m:
                raise RuntimeError(f'returnSingleton: {sig!r} does not return an object type')
            t = m.group(1)
            insert = f'\n    sget-object v0, {t}->{field}:{t}\n\n    return-object v0\n'
            src2, ok = insert_at_method_start(src, re.escape(sig), insert, need_regs=1)
            if ok:
                src = src2
                done.append(_rel(tree, f) + ' :: ' + sig[:60])
        if src != orig:
            write(f, src)
    _check_expect('returnSingleton ' + sig_regex[:50], len(done), expect)
    return done


def op_set_method_body(tree, sig_regex: 'str', body: 'text', file_regex: 'str' = None,
                       file_all: 'strs' = None, file_any: 'strs' = None,
                       sig_exclude: 'strs' = None, body_all: 'strs' = None,
                       body_none: 'strs' = None, expect: 'expect' = 1):
    """Replace the WHOLE body of every selected method with `body`: the method becomes
    `.method <sig>\\n<body>\\n.end method` (everything between the header and `.end method`,
    annotations included, is replaced). `expect` is checked against the number of selected
    methods."""
    done = []
    for f, src, sigs in _select_methods(tree, file_regex, file_all, file_any, sig_regex,
                                        sig_exclude, body_all, body_none):
        orig = src
        for sig in sigs:
            span = method_span(src, re.escape(sig))
            new = '.method ' + sig + '\n' + body + '\n.end method'
            if src[span[0]:span[1]] != new:
                src = src[:span[0]] + new + src[span[1]:]
            done.append(_rel(tree, f) + ' :: ' + sig[:60])
        if src != orig:
            write(f, src)
    _check_expect('setMethodBody ' + sig_regex[:50], len(done), expect)
    return done


def enum_field_for_name(tree, enum_type, const_name):
    """Static field of enum `enum_type` ('Lv8;') whose constant is named `const_name`
    (read from <clinit>: const-string "NAME" ... sput-object X, LE;->field)."""
    path = class_smali_path(tree, enum_type[1:-1])
    if not path:
        raise RuntimeError(f'enum {enum_type}: smali not found')
    src = read(path)
    m = re.search(r'const-string \w+, "' + re.escape(const_name) + r'"(?:(?!const-string).)*?'
                  r'sput-object \w+, ' + re.escape(enum_type) + r'->(\w+):', src, re.S)
    if not m:
        raise RuntimeError(f'enum {enum_type}: constant {const_name} not found')
    return m.group(1)


def op_insert_in_method_at_match(tree, sig_regex: 'str', pattern: 'str', insert: 'text',
                                 file_regex: 'str' = None, file_all: 'strs' = None,
                                 file_any: 'strs' = None, sig_exclude: 'strs' = None,
                                 body_all: 'strs' = None, body_none: 'strs' = None,
                                 enum_fields: 'enumfields' = None,
                                 expect: 'expect' = 1):
    """Insert a rendered template inside every selected method, at the position of a
    regex match. `pattern` is searched (no flags, `.` does not match "\\n") in the method text
    (header included); no match is an error. It must define the named group `at`: the
    template is inserted at the START of that group. The other named groups are the template
    variables. `enumFields` maps a variable name to {"enumType": "<capture name>", "constant":
    "<NAME>"}: the value is the static field of the enum class (the capture, e.g. `Lv8;`)
    whose constant is named NAME (read from the enum's <clinit>: const-string "NAME" ...
    sput-object X, LE;->field). `insert` may reference variables as `{{name}}` (an unknown
    name is an error). Idempotent: if the rendered text is already in the method, nothing is
    changed (the site is still counted)."""
    if not re.search(r'\(\?P<at>', pattern):
        raise OpArgError("insertInMethodAtMatch: pattern must define the named group 'at'")
    done = []
    for f, src, sigs in _select_methods(tree, file_regex, file_all, file_any, sig_regex,
                                        sig_exclude, body_all, body_none):
        orig = src
        for sig in sigs:
            span = method_span(src, re.escape(sig))
            text = src[span[0]:span[1]]
            m = re.search(pattern, text)
            if not m:
                raise RuntimeError(f'{sig[:50]}: insertion pattern not found '
                                   '(did the base change? check the anchors)')
            names = {k: v for k, v in m.groupdict().items() if k != 'at'}
            for var, spec in (enum_fields or {}).items():
                if var in names:
                    raise RuntimeError(f'enumFields variable {var!r} collides with a capture')
                names[var] = enum_field_for_name(tree, names[spec['enumType']],
                                                 spec['constant'])

            def sub(mm):
                if mm.group(1) not in names:
                    raise RuntimeError(f'unknown template variable {{{{{mm.group(1)}}}}}')
                return names[mm.group(1)]
            rendered = re.sub(r'\{\{(\w+)\}\}', sub, insert)
            if rendered in text:
                done.append(_rel(tree, f) + ' :: already applied')
                continue
            at = span[0] + m.start('at')
            src = src[:at] + rendered + src[at:]
            done.append(_rel(tree, f) + ' :: ' + sig[:60] + ' (+insert at match)')
        if src != orig:
            write(f, src)
    _check_expect('insertInMethodAtMatch ' + sig_regex[:50], len(done), expect)
    return done


# ---------------------------------------------------------------- whole-tree smali ops

def op_swap_const(tree, file_regex: 'str', old: 'str', new: 'str', context: 'str' = None,
                  expect: 'expect' = 'atLeastOne'):
    """In every file whose relative path matches `fileRegex` and where the text `old` occurs
    EXACTLY once (files with zero or several occurrences are skipped), replace it with `new`.
    If `context` is given the file must also contain it. `expect` is checked against the number
    of changed files."""
    done = []
    for f in smali_files(tree):
        if not re.search(file_regex, _rel(tree, f)):
            continue
        src = read(f)
        if src.count(old) != 1:
            continue
        if context and context not in src:
            continue
        write(f, src.replace(old, new, 1))
        done.append(_rel(tree, f))
    _check_expect('swapConst ' + old[:40], len(done), expect)
    return done


def op_enum_ordinals_zero(tree, file_regex: 'str', values: 'strs',
                          expect: 'expect' = 'atLeastOne'):
    """Collapse enum ordinals to 0 (any rejection reason becomes 'ok'). In every file whose
    relative path matches `fileRegex`, for each `v` in `values` (e.g. "0x1"): if the text
    `const/4 <reg>, <v>` occurs exactly once, it becomes `const/4 <reg>, 0x0`. `expect` is
    checked against the number of changed files."""
    done = []
    for f in smali_files(tree):
        if not re.search(file_regex, _rel(tree, f)):
            continue
        src = read(f)
        changed = False
        for v in values:
            m = re.search(r'const/4 (v\d), ' + re.escape(v), src)
            if m and src.count(m.group(0)) == 1:
                src = src.replace(m.group(0), f'const/4 {m.group(1)}, 0x0', 1)
                changed = True
        if changed:
            write(f, src)
            done.append(_rel(tree, f))
    _check_expect('enumOrdinalsZero', len(done), expect)
    return done


# pattern: const-string R, "X" ... invoke-virtual {A, R}, String->contains/compareTo/equals
# (invoke regex, invoke replacement text) - kept apart because smali references
# methods with literal parentheses while the regex escapes them.
COMPARE_INVOKES = {
    'contains': (r'Ljava/lang/String;->contains\(Ljava/lang/CharSequence;\)Z',
                 'Ljava/lang/String;->contains(Ljava/lang/CharSequence;)Z'),
    'compareToIgnoreCase': (r'Ljava/lang/String;->compareToIgnoreCase\(Ljava/lang/String;\)I',
                            'Ljava/lang/String;->compareToIgnoreCase(Ljava/lang/String;)I'),
    'equals': (r'Ljava/lang/String;->equals\(Ljava/lang/Object;\)Z',
               'Ljava/lang/String;->equals(Ljava/lang/Object;)Z'),
}


def op_sweep_string_compare_auto_true(tree, const_value: 'str', replace_value: 'str' = None,
                                      compare: 'strs' = None, exclude_regex: 'strs' = None,
                                      expect: 'expect' = 'atLeastOne'):
    """Turn comparisons against the string constant `constValue` into auto-true. For each
    `const-string <reg>, "<constValue>"` (reg is v# or p#), look in the next 600 characters
    for `invoke-virtual {<A>, <reg>}, Ljava/lang/String;->M(...)` where M is the first
    (in `compare` order) of the listed comparison names that occurs: "contains",
    "compareToIgnoreCase", "equals" (default: all three in that order). That invoke becomes
    `invoke-virtual {<reg>, <reg>}, <same method>` (a value compared with itself) and, if
    `replaceValue` is given and differs, that one constant becomes
    `const-string <reg>, "<replaceValue>"`. Files whose relative path matches any regex of
    `excludeRegex` (search) are skipped. `expect` is checked against the number of changed
    files."""
    names = compare or ['contains', 'compareToIgnoreCase', 'equals']
    invokes = [COMPARE_INVOKES[n] for n in names]
    done = []
    for f in smali_files(tree):
        rel = _rel(tree, f)
        if any(re.search(g, rel) for g in exclude_regex or ()):
            continue
        src = read(f)
        orig = src
        edits = []
        for m in re.finditer(r'const-string ((?:v|p)\d), "' + re.escape(const_value) + r'"', src):
            reg = m.group(1)
            tail = src[m.end():m.end() + 600]
            for inv_pat, inv_text in invokes:
                m2 = re.search(r'invoke-virtual \{((?:v|p)\d), ' + reg + r'\}, ' + inv_pat, tail)
                if m2:
                    edits.append((m.end() + m2.start(), m.end() + m2.end(),
                                  f'invoke-virtual {{{reg}, {reg}}}, {inv_text}'))
                    if replace_value and replace_value != const_value:
                        # rewrite ONLY this constant (not the whole file)
                        edits.append((m.start(), m.end(),
                                      f'const-string {reg}, "{replace_value}"'))
                    break
        # applied in reverse order so earlier offsets stay valid
        for s, e, t in sorted(edits, reverse=True):
            src = src[:s] + t + src[e:]
        if src != orig:
            write(f, src)
            done.append(rel)
    _check_expect('sweepStringCompareAutoTrue ' + const_value, len(done), expect)
    return done


def op_force_const_after_move(tree, anchor: 'str', value: 'str' = '0x1',
                              expect: 'expect' = 'atLeastOne'):
    """Force a check result. In every file containing `anchor`, take the LAST
    `move-result <vN>` before the first occurrence of `anchor` and add `const/4 <vN>, <value>`
    after it (separated by a blank line). Files with no `move-result v#` before the anchor are
    skipped. `expect` is checked against the number of changed files."""
    done = []
    for f in smali_files(tree):
        src = read(f)
        if anchor not in src:
            continue
        i = src.find(anchor)
        seg = src[:i]
        m = None
        for mm in re.finditer(r'move-result (v\d)\n', seg):
            m = mm
        if not m:
            continue
        reg = m.group(1)
        insert = f'move-result {reg}\n\n    const/4 {reg}, {value}\n'
        seg2 = seg[:m.start()] + insert
        src = seg2 + src[m.end():]
        write(f, src)
        done.append(_rel(tree, f))
    _check_expect('forceConstAfterMove', len(done), expect)
    return done


def op_replace_const_string_exact(tree, old: 'str', new: 'str', expect: 'expect' = 'atLeastOne'):
    """Replace every `const-string <reg>, "<old>"` (reg is v# or p#) whose value is EXACTLY
    `old` (the closing quote counts) with `<new>`, in every file. `expect` is checked
    against the number of changed files."""
    done = []
    for f in smali_files(tree):
        src = read(f)
        pat = re.compile(r'const-string ((?:v|p)\d), "' + re.escape(old) + r'"')
        if pat.search(src):
            src = pat.sub(lambda m: f'const-string {m.group(1)}, "{new}"', src)
            write(f, src)
            done.append(_rel(tree, f))
    _check_expect('replaceConstStringExact ' + old[:50], len(done), expect)
    return done


def op_replace_all_in_files_with_anchor(tree, file_contains: 'str', old: 'str', new: 'str',
                                        expect: 'expect' = 'atLeastOne'):
    """In every file that contains the text `fileContains` AND the text `old`, replace every
    occurrence of `old` with `new`. `expect` is checked against the number of changed files."""
    done = []
    for f in smali_files(tree):
        src = read(f)
        if file_contains not in src or old not in src:
            continue
        write(f, src.replace(old, new))
        done.append(_rel(tree, f) + ' :: ' + old[:50])
    _check_expect('replaceAllInFilesWithAnchor ' + old[:40], len(done), expect)
    return done


def op_force_invoke_result(tree, file_anchor: 'str', invoke_regex: 'str',
                           preceding_const: 'str' = None, value: 'str' = '0x1',
                           expect: 'expect' = 'atLeastOne'):
    """Force the result of a specific invoke. In every file containing `fileAnchor`, find the
    first `invoke-<kind> {...}, <invokeRegex>` (optionally requiring `precedingConst` within
    the 120 characters before it); if a `move-result <reg>` follows within 60 characters,
    insert `const/4 <reg>, <value>` after it (blank line before). Only the first usable
    site per file is patched. `expect` is checked against the number of changed files."""
    done = []
    for f in smali_files(tree):
        src = read(f)
        if file_anchor and file_anchor not in src:
            continue
        rel = _rel(tree, f)
        changed = False
        for m in re.finditer(r'invoke-[\w]+ \{[^}]+\}, ' + invoke_regex, src):
            if preceding_const:
                pre = src[max(0, m.start() - 200):m.start()]
                if preceding_const not in pre[-120:]:
                    continue
            tail = src[m.end():m.end() + 60]
            m2 = re.search(r'\n\s*move-result ((?:v|p)\d+)', tail)
            if not m2:
                continue
            reg = m2.group(1)
            ins_at = m.end() + m2.end()
            src = src[:ins_at] + f'\n\n    const/4 {reg}, {value}' + src[ins_at:]
            changed = True
            done.append(f'{rel} :: {invoke_regex[:50]} -> {value}')
            break  # only the first site per file
        if changed:
            write(f, src)
    _check_expect('forceInvokeResult', len(done), expect)
    return done


# ---------------------------------------------------------------- class-targeted ops
# Used by families whose input is pinned to one exact build (e.g. the alarm fork): the class
# is named explicitly and every edit asserts what it found, so a different build fails loudly
# instead of being patched by a heuristic.

def class_smali_path(tree, name):
    """Path of the .smali of class `name` (type without L or ;, e.g. 'v8') or None."""
    rel = name + '.smali'
    for d in sorted(os.listdir(tree)):
        if d == 'smali' or d.startswith('smali_classes'):
            p = os.path.join(tree, d, rel)
            if os.path.exists(p):
                return p
    return None


def class_source(tree, cls):
    """(path, source) of class `cls` (e.g. 'w2/k'). Raises RuntimeError if it is missing."""
    path = class_smali_path(tree, cls)
    if not path:
        raise RuntimeError(f'class {cls}: smali not found (did the base change?)')
    return path, read(path)


def op_verify_class_contains(tree, checks: 'checks'):
    """Assert that classes still hold the members something else depends on. `checks` is a
    list of {"className": "<type without L/;>", "contains": "<text>"}; for each, the class
    smali (looked up in every smali* dir) must exist and contain the text. Any failure is an
    error. Changes nothing."""
    done = []
    for chk in checks:
        cls, needle = chk['className'], chk['contains']
        _path, src = class_source(tree, cls)
        if needle not in src:
            raise RuntimeError(f'{cls}: missing {needle!r} (a dependent component needs it)')
        done.append(f'{cls} :: {needle.strip()}')
    return done


def op_replace_in_class(tree, class_name: 'str', old: 'text', new: 'text', count: 'int' = 1):
    """Replace the exact text `old` with `new` in the class `className` (e.g. `w2/k`). `old`
    must occur exactly `count` times, otherwise RuntimeError; all occurrences are replaced."""
    path, src = class_source(tree, class_name)
    found = src.count(old)
    if found != count:
        raise RuntimeError(f'{class_name}: expected {count} occurrence(s) of {old[:70]!r} and '
                           f'found {found} (did the base change? check the anchors)')
    write(path, src.replace(old, new))
    return [f'{class_name} :: {old.strip().splitlines()[0][:60]} x{count}']


def _class_method_span(src, cls, header):
    """Span of the single method of `src` whose header is exactly `header`."""
    pat = re.compile(r'^\.method ' + re.escape(header) + r'\n', re.M)
    hits = list(pat.finditer(src))
    if len(hits) != 1:
        raise RuntimeError(f'{cls}: expected one method {header!r} and found {len(hits)} '
                           f'(did the base change? check the anchors)')
    end = src.find('\n.end method', hits[0].start())
    return hits[0].start(), end + len('\n.end method')


def op_replace_class_method(tree, class_name: 'str', header: 'str', body: 'text',
                            must_contain: 'strs' = None):
    """Replace the whole method with the exact `.method` line `header` (without the `.method `
    prefix) of class `className` by `body` (everything between the header line and
    `.end method`). Exactly one such method must exist, and its old text must contain every
    `mustContain` string (guards against replacing a different method)."""
    path, src = class_source(tree, class_name)
    start, end = _class_method_span(src, class_name, header)
    old = src[start:end]
    for needle in must_contain or ():
        if needle not in old:
            raise RuntimeError(f'{class_name}.{header}: old body lacks {needle!r} '
                               f'(did the base change? check the anchors)')
    write(path, src[:start] + '.method ' + header + '\n' + body + '\n.end method' +
          src[end:])
    return [f'{class_name} :: {header[:60]}']


def op_insert_in_class_method(tree, class_name: 'str', header: 'str', insert: 'text'):
    """Insert `insert` right after the `.locals` line of the method with the exact `.method`
    line `header` of class `className` (exactly one such method; it must have a `.locals`)."""
    path, src = class_source(tree, class_name)
    start, end = _class_method_span(src, class_name, header)
    body = src[start:end]
    m = re.search(r'\n[ ]*\.locals \d+\n', body)
    if not m:
        raise RuntimeError(f'{class_name}.{header}: no .locals line')
    body = body[:m.end()] + insert + body[m.end():]
    write(path, src[:start] + body + src[end:])
    return [f'{class_name} :: {header[:60]} (+insert)']


def op_remove_smali_dir(tree, rel: 'str'):
    """Delete the directory `rel` (e.g. `appfunctions_aggregated_deps`) from every `smali*`
    dir of the tree. RuntimeError if it exists in none."""
    import shutil
    done = []
    for d in sorted(os.listdir(tree)):
        if d == 'smali' or d.startswith('smali_classes'):
            p = os.path.join(tree, d, rel)
            if os.path.isdir(p):
                n = sum(len(fs) for _r, _d, fs in os.walk(p))
                shutil.rmtree(p)
                done.append(f'{d}/{rel} removed ({n} files)')
    if not done:
        raise RuntimeError(f'smali dir {rel}: not found (did the base change?)')
    return done


# ---------------------------------------------------------------- resources

def op_add_string_resources(tree, name: 'str', values: 'strmap'):
    """Add `<string name="<name>">` to `res/values<qualifier>/strings.xml`. `values` maps the
    qualifier ("" for the default, "-es", ...) to the text. The name must not exist yet in any
    of the `res/values*/strings.xml` files and every target file must exist (and have exactly
    one `</resources>`). The string is appended before `</resources>` (4-space indent,
    XML-escaped text)."""
    from xml.sax.saxutils import escape
    res = os.path.join(tree, 'res')
    needle = f'name="{name}"'
    for d in os.listdir(res):
        p = os.path.join(res, d, 'strings.xml')
        if d.startswith('values') and os.path.exists(p):
            with open(p, encoding='utf-8') as f:
                if needle in f.read():
                    raise RuntimeError(f'string {name} already exists in {d}/strings.xml')
    done = []
    for qualifier, text in values.items():
        p = os.path.join(res, 'values' + qualifier, 'strings.xml')
        if not os.path.exists(p):
            raise RuntimeError(f'res/values{qualifier}/strings.xml not found')
        with open(p, encoding='utf-8') as f:
            src = f.read()
        if src.count('</resources>') != 1:
            raise RuntimeError(f'res/values{qualifier}/strings.xml: unexpected structure')
        src = src.replace('</resources>', f'    <string name="{name}">{escape(text)}</string>\n</resources>')
        with open(p, 'w', encoding='utf-8') as f:
            f.write(src)
        done.append(f'res/values{qualifier}/strings.xml :: + {name}')
    return done


def op_define_resource_entry(tree, type: 'str', name: 'str', id: 'str'):
    """Define the resource `<type>/<name>` at the id `id` (`0x` + 8 hex digits, e.g.
    `0x7f010000`) with the value `@null` (aapt2: a reference with data 0), in the default
    configuration only; nothing in the app references or loads it. Used to fill a slot the
    vendor build stripped (e.g. the first `anim` id, which Google Play services uses to find an
    app's resources package). Preconditions, otherwise RuntimeError: `res/values/public.xml`
    declares `<type>` entries with the id's type byte (`0x01` in `0x7f01xxxx`); no `public`
    entry has this id, and no `public` entry of `<type>` is named `<name>`. Effect: a
    `<public type name id/>` line in `res/values/public.xml` and `<item type="<type>"
    name="<name>">@null</item>` in `res/values/<type>s.xml` (created if missing, otherwise
    appended; the name must not appear there). The compiled result is checked by the Kotlin
    engine's equivalent (ARSC) op, which also fails when the slot is defined in any
    configuration."""
    from xml.sax.saxutils import quoteattr
    if not re.fullmatch(r'0x[0-9a-fA-F]{8}', id):
        raise OpArgError(f'defineResourceEntry.id: expected 0x + 8 hex digits, got {id!r}')
    rid = int(id, 16)
    pub = os.path.join(tree, 'res', 'values', 'public.xml')
    if not os.path.exists(pub):
        raise RuntimeError('res/values/public.xml not found')
    src = read(pub)
    entries = [(m.group(1), m.group(2), int(m.group(3), 16)) for m in re.finditer(
        r'<public\s+type="([^"]*)"\s+name="([^"]*)"\s+id="(0x[0-9a-fA-F]+)"', src)]
    if not any(t == type and (i >> 16) == (rid >> 16) for t, _n, i in entries):
        raise RuntimeError(f'public.xml: no {type} entries with type id 0x{(rid >> 16) & 0xff:02x} '
                           f'(did the base change? check the anchors)')
    if any(i == rid for _t, _n, i in entries):
        raise RuntimeError(f'resource id {id} is already defined')
    if any(t == type and n == name for t, n, _i in entries):
        raise RuntimeError(f'resource {type}/{name} already exists')
    if src.count('</resources>') != 1:
        raise RuntimeError('res/values/public.xml: unexpected structure')
    vals = os.path.join(tree, 'res', 'values', type + 's.xml')
    if os.path.exists(vals):
        vsrc = read(vals)
        if f'name="{name}"' in vsrc:
            raise RuntimeError(f'res/values/{type}s.xml already declares {name}')
        if vsrc.count('</resources>') != 1:
            raise RuntimeError(f'res/values/{type}s.xml: unexpected structure')
    else:
        vsrc = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n</resources>\n"
    write(pub, src.replace('</resources>', f'    <public type={quoteattr(type)} name={quoteattr(name)} '
                                            f'id="{id}" />\n</resources>'))
    write(vals, vsrc.replace('</resources>', f'    <item type={quoteattr(type)} name={quoteattr(name)}>'
                                              '@null</item>\n</resources>'))
    return [f'res/values/public.xml :: + {type}/{name} {id}',
            f'res/values/{type}s.xml :: + {name} = @null']


# ---------------------------------------------------------------- manifest: text ops

def op_manifest_rebrand(tree, old_pkg: 'str', new_pkg: 'str'):
    """Text rewrite of AndroidManifest.xml (not a structural edit), in this order:
    `package="<oldPkg>"` -> `package="<newPkg>"`; in `<permission ...>` and
    `<uses-permission ...>` tags, `android:name="<oldPkg>.` -> `android:name="<newPkg>.`
    (prefix only); `android:authorities="<oldPkg>.`, `android:readPermission="<oldPkg>.` and
    `android:permission="<oldPkg>.` -> same with `<newPkg>.` (prefix only). It does NOT touch
    `android:name` of classes (application/activity/service/receiver/provider) nor deeplink
    schemes/hosts. Logs one entry if the file changed, none otherwise."""
    done = []
    p = os.path.join(tree, 'AndroidManifest.xml')
    src = read(p)
    orig = src
    src = src.replace(f'package="{old_pkg}"', f'package="{new_pkg}"')
    # only defined/requested permissions and authorities; NOT the android:name of classes
    # (application/activity/service/receiver/provider refer to code classes)
    src = re.sub(r'(<permission\s[^>]*android:name=")' + re.escape(old_pkg) + r'\.',
                 r'\g<1>' + new_pkg + '.', src)
    src = re.sub(r'(<uses-permission\s[^>]*android:name=")' + re.escape(old_pkg) + r'\.',
                 r'\g<1>' + new_pkg + '.', src)
    src = src.replace(f'android:authorities="{old_pkg}.', f'android:authorities="{new_pkg}.')
    src = src.replace(f'android:readPermission="{old_pkg}.', f'android:readPermission="{new_pkg}.')
    src = src.replace(f'android:permission="{old_pkg}.', f'android:permission="{new_pkg}.')
    if src != orig:
        write(p, src)
        done.append('AndroidManifest.xml :: rebrand ' + old_pkg + ' -> ' + new_pkg)
    return done


def op_manifest_add_query_package(tree, pkg: 'str'):
    """Text edit of AndroidManifest.xml: add `<package android:name="<pkg>"/>` right after
    the first `<queries>` tag (on its own line, 8-space indent). Already present: nothing is
    changed (logged). No `<queries>` block: error."""
    p = os.path.join(tree, 'AndroidManifest.xml')
    src = read(p)
    tag = f'<package android:name="{pkg}"/>'
    if tag in src:
        return [f'AndroidManifest.xml :: <queries> already declares {pkg}']
    if '<queries>' not in src:
        raise RuntimeError('manifest has no <queries> block')
    write(p, src.replace('<queries>', '<queries>\n        ' + tag, 1))
    return [f'AndroidManifest.xml :: <queries> + {pkg}']


# ---------------------------------------------------------------- manifest: structural ops
# The manifest is parsed (ElementTree), edited and written back (UTF-8, XML declaration,
# 4-space indentation). `android:` attribute names are in the android namespace; other
# attribute names must be plain (no prefix). A "select" is an object, all parts ANDed:
#   path          "." (the root), "**" (every element), or "a/b/c": child tags relative to the
#                 root (ElementTree findall); a list of paths selects their union
#   attr          {name: value}   the attribute equals value
#   attrIn        {name: [values]}   the attribute is one of the values
#   simpleNameIn  {name: [simple names]}   the part after the last "." of the attribute is one
#                 of them (class names without package)
#   parentAttr    {name: value}   attributes of the parent element
#   hasChild      [{"tag": t, "attr": {name: value}}, ...]  every entry has a direct child
#                 with that tag and attributes
# Counts are asserted with `expect`: an exact integer, "atLeastOne", or "eachListed" (only with
# one attrIn/simpleNameIn entry: every listed value is matched exactly once and nothing else).

_ANDROID_NS = 'http://schemas.android.com/apk/res/android'
_SELECT_KEYS = {'path', 'attr', 'attrIn', 'simpleNameIn', 'parentAttr', 'hasChild'}
_PATH_RE = re.compile(r'^(\.|\*\*|[\w.-]+(/[\w.-]+)*)$')


def _attr_name(name):
    if name.startswith('android:'):
        return f'{{{_ANDROID_NS}}}{name[8:]}'
    if ':' in name:
        raise OpArgError(f'attribute {name!r}: only the android: prefix is supported')
    return name


def _validate_select(sel, what='select'):
    if not isinstance(sel, dict):
        raise OpArgError(f'{what}: must be an object')
    extra = set(sel) - _SELECT_KEYS
    if extra:
        raise OpArgError(f'{what}: unknown key(s) {sorted(extra)}')
    path = sel.get('path')
    paths = [path] if isinstance(path, str) else path
    if not paths or not isinstance(paths, list) or not all(
            isinstance(p, str) and _PATH_RE.match(p) for p in paths):
        raise OpArgError(f'{what}: "path" must be a path string or a non-empty list of them')
    for key in ('attr', 'parentAttr'):
        if key in sel:
            _validate_strmap(sel[key], f'{what}.{key}')
            [_attr_name(k) for k in sel[key]]
    for key in ('attrIn', 'simpleNameIn'):
        if key in sel:
            v = sel[key]
            if not isinstance(v, dict) or not all(
                    isinstance(x, list) and all(isinstance(i, str) for i in x)
                    for x in v.values()):
                raise OpArgError(f'{what}.{key}: must map attribute names to lists of strings')
            [_attr_name(k) for k in v]
    if 'hasChild' in sel:
        hc = sel['hasChild']
        if not isinstance(hc, list) or not all(
                isinstance(c, dict) and set(c) <= {'tag', 'attr'} and isinstance(c.get('tag'), str)
                for c in hc):
            raise OpArgError(f'{what}.hasChild: must be a list of {{"tag", "attr"}} objects')
        for c in hc:
            if 'attr' in c:
                _validate_strmap(c['attr'], f'{what}.hasChild.attr')
                [_attr_name(k) for k in c['attr']]


def _select_elements(root, sel):
    paths = [sel['path']] if isinstance(sel['path'], str) else sel['path']
    parents = {c: p for p in root.iter() for c in p}
    seen, out = set(), []
    for path in paths:
        if path == '.':
            cands = [root]
        elif path == '**':
            cands = list(root.iter())
        else:
            cands = root.findall(path)
        for e in cands:
            if id(e) in seen:
                continue
            if any(e.get(_attr_name(k)) != v for k, v in sel.get('attr', {}).items()):
                continue
            if any(e.get(_attr_name(k)) not in vals for k, vals in sel.get('attrIn', {}).items()):
                continue
            if any((e.get(_attr_name(k)) or '\0').rsplit('.', 1)[-1] not in vals
                   for k, vals in sel.get('simpleNameIn', {}).items()):
                continue
            if 'parentAttr' in sel:
                par = parents.get(e)
                if par is None or any(par.get(_attr_name(k)) != v
                                      for k, v in sel['parentAttr'].items()):
                    continue
            if any(not any(c.tag == spec['tag'] and all(
                    c.get(_attr_name(k)) == v for k, v in spec.get('attr', {}).items())
                    for c in e) for spec in sel.get('hasChild', ())):
                continue
            seen.add(id(e))
            out.append(e)
    return out, parents


def _describe_select(sel):
    return ' '.join(f'{k}={sel[k]}' for k in sel)[:90]


def _expect_elements(sel, elems, expect):
    label = 'manifest select ' + _describe_select(sel)
    if expect == 'eachListed':
        keys = [k for k in ('attrIn', 'simpleNameIn') if k in sel]
        if len(keys) != 1 or len(sel[keys[0]]) != 1:
            raise OpArgError('expect "eachListed" needs exactly one attrIn/simpleNameIn entry')
        (attr, wanted), = sel[keys[0]].items()
        found = [(e.get(_attr_name(attr)) or '').rsplit('.', 1)[-1] if keys[0] == 'simpleNameIn'
                 else e.get(_attr_name(attr)) for e in elems]
        if sorted(found) != sorted(wanted):
            raise RuntimeError(f'{label}: expected {sorted(wanted)} and matched {sorted(found)} '
                               '(did the base change? check the anchors)')
        return
    _check_expect(label, len(elems), expect)


def op_manifest_expect(tree, select: 'select', expect: 'mexpect'):
    """Assert how many manifest elements `select` matches (see the manifest select syntax
    above). Changes nothing; logs one line."""
    def edit(root, an):
        elems, _ = _select_elements(root, select)
        _expect_elements(select, elems, expect)
        return [f'manifest: {len(elems)} element(s) match {_describe_select(select)}']
    return manifest_edit(tree, edit)


def op_manifest_remove(tree, select: 'select', expect: 'mexpect'):
    """Remove every manifest element matched by `select` (with its subtree) after asserting
    the count with `expect`. A matched root is an error."""
    def edit(root, an):
        elems, parents = _select_elements(root, select)
        _expect_elements(select, elems, expect)
        for e in elems:
            if e not in parents:
                raise RuntimeError('cannot remove the manifest root')
            parents[e].remove(e)
        return [f'manifest: {len(elems)} element(s) removed ({_describe_select(select)})']
    return manifest_edit(tree, edit)


def op_manifest_set_attr(tree, select: 'select', attrs: 'strmap', expect: 'mexpect'):
    """On every manifest element matched by `select` (count asserted with `expect`), set each
    attribute of `attrs` ({name: value}; an existing value is replaced, a missing one added)."""
    def edit(root, an):
        elems, _ = _select_elements(root, select)
        _expect_elements(select, elems, expect)
        for e in elems:
            for k, v in attrs.items():
                e.set(_attr_name(k), v)
        return [f'manifest: {len(elems)} element(s) set {attrs} ({_describe_select(select)})']
    return manifest_edit(tree, edit)


def op_manifest_remove_attr(tree, select: 'select', attrs: 'strs', expect: 'mexpect'):
    """On every manifest element matched by `select` (count asserted with `expect`), remove
    each attribute of `attrs`; an element lacking one of them is an error."""
    def edit(root, an):
        elems, _ = _select_elements(root, select)
        _expect_elements(select, elems, expect)
        for e in elems:
            for k in attrs:
                if e.attrib.pop(_attr_name(k), None) is None:
                    raise RuntimeError(f'manifest element has no {k} (did the base change?)')
        return [f'manifest: {len(elems)} element(s) lost {attrs} ({_describe_select(select)})']
    return manifest_edit(tree, edit)


def _build_child(parent, spec):
    import xml.etree.ElementTree as ET
    e = ET.SubElement(parent, spec['tag'])
    for k, v in spec.get('attrs', {}).items():
        e.set(_attr_name(k), v)
    for child in spec.get('children', ()):
        _build_child(e, child)


def op_manifest_add_child(tree, parent: 'select', tag: 'str', attrs: 'strmap' = None,
                          children: 'children' = None, expect: 'mexpect' = 1):
    """Append a new element as the LAST child of every manifest element matched by `parent`
    (count asserted with `expect`, default exactly 1). The element is `<tag>` with `attrs`
    set in the given order and the nested `children` ([{"tag", "attrs", "children"}, ...],
    appended in order)."""
    def edit(root, an):
        elems, _ = _select_elements(root, parent)
        _expect_elements(parent, elems, expect)
        spec = {'tag': tag, 'attrs': attrs or {}, 'children': children or []}
        for e in elems:
            _build_child(e, spec)
        return [f'manifest: <{tag}> added to {len(elems)} element(s) ({_describe_select(parent)})'
                + (f' {attrs}' if attrs else '')]
    return manifest_edit(tree, edit)


def manifest_edit(tree, fn):
    """Parse AndroidManifest.xml, call fn(root, an) -> list of log entries, write it back.
    `an(name)` expands an attribute name into the android namespace."""
    import xml.etree.ElementTree as ET
    ET.register_namespace('android', _ANDROID_NS)
    path = os.path.join(tree, 'AndroidManifest.xml')
    et = ET.parse(path)
    log = fn(et.getroot(), lambda name: f'{{{_ANDROID_NS}}}{name}')
    ET.indent(et, space='    ')
    et.write(path, encoding='utf-8', xml_declaration=True)
    return log


# ---------------------------------------------------------------- registry

OPS = {
    'insertAtMethodStart': op_insert_at_method_start,
    'returnSingleton': op_return_singleton,
    'setMethodBody': op_set_method_body,
    'insertInMethodAtMatch': op_insert_in_method_at_match,
    'swapConst': op_swap_const,
    'enumOrdinalsZero': op_enum_ordinals_zero,
    'sweepStringCompareAutoTrue': op_sweep_string_compare_auto_true,
    'forceConstAfterMove': op_force_const_after_move,
    'replaceConstStringExact': op_replace_const_string_exact,
    'replaceAllInFilesWithAnchor': op_replace_all_in_files_with_anchor,
    'forceInvokeResult': op_force_invoke_result,
    'verifyClassContains': op_verify_class_contains,
    'replaceInClass': op_replace_in_class,
    'replaceClassMethod': op_replace_class_method,
    'insertInClassMethod': op_insert_in_class_method,
    'removeSmaliDir': op_remove_smali_dir,
    'addStringResources': op_add_string_resources,
    'defineResourceEntry': op_define_resource_entry,
    'manifestRebrand': op_manifest_rebrand,
    'manifestAddQueryPackage': op_manifest_add_query_package,
    'manifestExpect': op_manifest_expect,
    'manifestRemove': op_manifest_remove,
    'manifestSetAttr': op_manifest_set_attr,
    'manifestRemoveAttr': op_manifest_remove_attr,
    'manifestAddChild': op_manifest_add_child,
}


def _snake(name):
    return re.sub(r'[A-Z]', lambda m: '_' + m.group(0).lower(), name)


def _is_int(v):
    return isinstance(v, int) and not isinstance(v, bool) and v >= 0


def _validate_strmap(v, what):
    if not isinstance(v, dict) or not all(isinstance(k, str) and isinstance(x, str)
                                          for k, x in v.items()):
        raise OpArgError(f'{what}: must be an object of strings')


def _validate_children(v, what):
    if not isinstance(v, list):
        raise OpArgError(f'{what}: must be a list')
    for c in v:
        if not isinstance(c, dict) or set(c) - {'tag', 'attrs', 'children'} or \
                not isinstance(c.get('tag'), str):
            raise OpArgError(f'{what}: entries are {{"tag", "attrs", "children"}} objects')
        if 'attrs' in c:
            _validate_strmap(c['attrs'], what + '.attrs')
            [_attr_name(k) for k in c['attrs']]
        if 'children' in c:
            _validate_children(c['children'], what + '.children')


def _normalise(tag, value, what):
    """Check `value` against type tag `tag`; return the value to pass to the function."""
    if tag == 'str' and isinstance(value, str):
        return value
    if tag == 'text':
        if isinstance(value, str):
            return value
        if isinstance(value, list) and all(isinstance(x, str) for x in value):
            return '\n'.join(value)
    elif tag == 'int' and _is_int(value):
        return value
    elif tag == 'bool' and isinstance(value, bool):
        return value
    elif tag == 'strs' and isinstance(value, list) and all(isinstance(x, str) for x in value):
        return value
    elif tag == 'strmap':
        _validate_strmap(value, what)
        return value
    elif tag == 'expect' and (_is_int(value) or value in ('atLeastOne', 'any')):
        return value
    elif tag == 'mexpect' and (_is_int(value) or value in ('atLeastOne', 'eachListed')):
        return value
    elif tag == 'select':
        _validate_select(value, what)
        return value
    elif tag == 'children':
        _validate_children(value, what)
        return value
    elif tag == 'checks':
        if isinstance(value, list) and value and all(
                isinstance(c, dict) and set(c) == {'className', 'contains'}
                and all(isinstance(x, str) for x in c.values()) for c in value):
            return value
    elif tag == 'enumfields':
        if isinstance(value, dict) and all(
                isinstance(s, dict) and set(s) == {'enumType', 'constant'}
                and all(isinstance(x, str) for x in s.values()) for s in value.values()):
            return value
    raise OpArgError(f'{what}: expected {tag}, got {value!r}'[:200])


def prepare_op(name, args):
    """Validate step `name` with JSON `args` (without the "op" key). Returns
    (function, kwargs); raises OpArgError for an unknown op, unknown/missing argument or a
    wrong type."""
    import inspect
    func = OPS.get(name)
    if func is None:
        raise OpArgError(f'unknown op {name!r}')
    params = {n: p for n, p in inspect.signature(func).parameters.items() if n != 'tree'}
    by_json = {}
    for n, p in params.items():
        camel = re.sub(r'_([a-z])', lambda m: m.group(1).upper(), n)
        by_json[camel] = (n, p)
    kwargs = {}
    for key, value in args.items():
        if key not in by_json:
            raise OpArgError(f'op {name}: unknown argument {key!r}')
        pyname, p = by_json[key]
        kwargs[pyname] = _normalise(p.annotation, value, f'op {name}.{key}')
    missing = [k for k, (n, p) in by_json.items()
               if p.default is inspect.Parameter.empty and n not in kwargs]
    if missing:
        raise OpArgError(f'op {name}: missing argument(s) {missing}')
    return func, kwargs
