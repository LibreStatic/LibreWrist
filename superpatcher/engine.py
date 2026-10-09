"""Super-patcher engine: smali operations anchored to stable strings/signatures.

Each operation is idempotent where possible and returns the list of touched sites
(an empty list means no match). See docs/patches.md.
"""
import os
import re


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


def op_return_const(tree, file_glob=None, anchor=None, sig_regex=None, value='0x0',
                    kind='int', body_anchor=None):
    """Insert const+return at the start of methods matching sig_regex.
    file_glob/anchor filter files; body_anchor requires the method BODY to contain
    the anchor (avoids patching the wrong method of the file)."""
    files = list(smali_files(tree)) if not (file_glob or anchor) else \
        [p for p in smali_files(tree) if (not file_glob or re.search(file_glob, p)) and
         (not anchor or anchor in read(p))]
    done = []
    for f in files:
        src = read(f)
        if kind == 'void':
            insert = '\n    return-void\n'
        elif kind == 'int':
            insert = f'\n    const/4 v0, {value}\n\n    return v0\n'
        else:
            insert = f'\n    {value}\n\n    return-object v0\n'
        changed = False
        for m in re.finditer(r'\.method\s+([^\n]+)\n(.*?)(?=\n\.end method)', src, re.S):
            sig, body = m.group(1), m.group(2)
            if not re.match(sig_regex.replace('\\', '\\'), sig) and not re.search(sig_regex, sig):
                continue
            if body_anchor and body_anchor not in body:
                continue
            src2, ok = insert_at_method_start(src, re.escape(sig), insert, need_regs=1)
            if ok:
                src = src2
                changed = True
                done.append(os.path.relpath(f, tree) + ' :: ' + sig[:55])
        if changed:
            write(f, src)
    return done


def op_swap_const(tree, file_glob, old, new, context=None):
    """Replace a constant (once per file), optionally validating a context string."""
    done = []
    for f in smali_files(tree):
        if not re.search(file_glob, f):
            continue
        src = read(f)
        if src.count(old) != 1:
            continue
        if context and context not in src:
            continue
        write(f, src.replace(old, new, 1))
        done.append(os.path.relpath(f, tree))
    return done


def op_enum_ordinals_zero(tree, file_glob, values):
    """Collapse enum ordinals to 0 (any rejection reason becomes 'ok')."""
    done = []
    for f in smali_files(tree):
        if not re.search(file_glob, f):
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
            done.append(os.path.relpath(f, tree))
    return done

# pattern: const-string R, "X" ... invoke-virtual {A, R}, String->contains/compareTo/equals
# (invoke regex, invoke replacement text) - kept apart because smali references
# methods with literal parentheses while the regex escapes them.
_CMP_INVOKES = (
    (r'Ljava/lang/String;->contains\(Ljava/lang/CharSequence;\)Z',
     'Ljava/lang/String;->contains(Ljava/lang/CharSequence;)Z'),
    (r'Ljava/lang/String;->compareToIgnoreCase\(Ljava/lang/String;\)I',
     'Ljava/lang/String;->compareToIgnoreCase(Ljava/lang/String;)I'),
    (r'Ljava/lang/String;->equals\(Ljava/lang/Object;\)Z',
     'Ljava/lang/String;->equals(Ljava/lang/Object;)Z'),
)


def sweep_string_compare_auto_true(tree, const_value, replace_value=None,
                                   invokes=_CMP_INVOKES, exclude_globs=()):
    """Turn comparisons against `const_value` into auto-true:
    invoke {A, R} -> {R, R} (y opcionalmente reescribe la constante).
    Edits are applied in reverse order so offsets stay valid."""
    done = []
    for f in smali_files(tree):
        rel = os.path.relpath(f, tree)
        if any(re.search(g, rel) for g in exclude_globs):
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
        for s, e, t in sorted(edits, reverse=True):
            src = src[:s] + t + src[e:]
        if src != orig:
            write(f, src)
            done.append(rel)
    return done


def op_force_const_after_move(tree, anchor, value='0x1'):
    """Force a check result: insert a const after the move-result that precedes
    `anchor` (e.g. decision log strings)."""
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
        done.append(os.path.relpath(f, tree))
    return done


def op_manifest_rebrand(tree, old_pkg, new_pkg):
    """Manifest: package=, permission names and provider authorities, old app-id -> new.
    Does NOT touch deeplink schemes/hosts (they must keep matching the other end)."""
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


def op_replace_const_string_exact(tree, old, new):
    """Replace const-string whose value is EXACTLY `old`."""
    done = []
    for f in smali_files(tree):
        src = read(f)
        pat = re.compile(r'const-string ((?:v|p)\d), "' + re.escape(old) + r'"')
        if pat.search(src):
            src = pat.sub(rf'const-string \1, "{new}"', src)
            write(f, src)
            done.append(os.path.relpath(f, tree))
    return done


def op_force_invoke_result(tree, file_anchor, invoke_regex, preceding_const=None, value='0x1'):
    """Force the result of a specific invoke: after the `move-result` that follows the
    invoke matching `invoke_regex` (optionally preceded within ~120 chars by
    `preceding_const`), insert `const/4 reg, value`."""
    done = []
    for f in smali_files(tree):
        src = read(f)
        if file_anchor and file_anchor not in src:
            continue
        rel = os.path.relpath(f, tree)
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
    return done


def op_return_singleton(tree, file_glob, method_sig_regex, singleton_pattern):
    """Method -> returns a singleton (sget + return-object)."""
    done = []
    for f in smali_files(tree):
        if not re.search(file_glob, f):
            continue
        src = read(f)
        m = re.search(r'\.method\s+' + method_sig_regex + r'\n\s*\.locals (\d+)\n', src)
        if not m:
            continue
        ret_type = re.search(r'\)L([^;]+);', m.group(0))
        if not ret_type:
            continue
        sget = singleton_pattern.format(type='L' + ret_type.group(1) + ';')
        insert = f'\n    {sget}\n\n    return-object v0\n'
        src2, ok = insert_at_method_start(src, method_sig_regex, insert, need_regs=1)
        if ok:
            write(f, src2)
            done.append(os.path.relpath(f, tree))
    return done


# ---------------------------------------------------------------- watch ops


def iter_methods(src):
    """(signature, body) of every method in a smali file."""
    for m in re.finditer(r'\.method\s+([^\n]+)\n(.*?)(?=\n\.end method)', src, re.S):
        yield m.group(1), m.group(2)


def find_methods(tree, sig_regex, body_all=(), file_all=(), expect=1, label=''):
    """Locate methods by signature (re.match against the .method line without the
    '.method ' prefix) and by strings the body must contain. Returns [(file, signature)].
    Raises RuntimeError if the number found is not `expect` (missing or ambiguous anchor)."""
    found = []
    for f in smali_files(tree):
        src = read(f)
        if any(a not in src for a in file_all):
            continue
        for sig, body in iter_methods(src):
            if re.match(sig_regex, sig) and all(a in body for a in body_all):
                found.append((f, sig))
    if len(found) != expect:
        raise RuntimeError(f'{label or sig_regex}: expected {expect} method(s) and '
                           f'found {len(found)} (did the base change? check the anchors)')
    return found


def op_insert_in_method(tree, sig_regex, insert, body_all=(), file_all=(), need_regs=1,
                        tail=None, label=''):
    """Insert `insert` at the start (after .locals) of the method found by find_methods.
    `tail`: optional text inserted before the LAST `return*` of the method.
    Idempotent: does not repeat if the text is already there."""
    done = []
    for f, sig in find_methods(tree, sig_regex, body_all, file_all, label=label):
        src = read(f)
        s2, ok = insert_at_method_start(src, re.escape(sig), insert, need_regs=need_regs)
        if not ok:
            raise RuntimeError(f'{label or sig}: no .locals after the signature in {f}')
        if tail:
            span = method_span(s2, re.escape(sig))
            body = s2[span[0]:span[1]]
            if tail not in body:
                idx = max(body.rfind('    return '), 0)
                body = body[:idx] + tail + body[idx:]
                s2 = s2[:span[0]] + body + s2[span[1]:]
        if s2 != src:
            write(f, s2)
        done.append(os.path.relpath(f, tree) + ' :: ' + sig[:60])
    return done


def op_set_method_body(tree, sig_regex, body_text, body_all=(), file_all=(), label=''):
    """Replace the WHOLE body (after the signature) of the method found by find_methods."""
    done = []
    for f, sig in find_methods(tree, sig_regex, body_all, file_all, label=label):
        src = read(f)
        span = method_span(src, re.escape(sig))
        new = '.method ' + sig + '\n' + body_text + '\n.end method'
        if src[span[0]:span[1]] != new:
            write(f, src[:span[0]] + new + src[span[1]:])
        done.append(os.path.relpath(f, tree) + ' :: ' + sig[:60])
    return done


def op_manifest_add_query_package(tree, pkg):
    """Add <package android:name=pkg/> as the first child of the manifest <queries>."""
    p = os.path.join(tree, 'AndroidManifest.xml')
    src = read(p)
    tag = f'<package android:name="{pkg}"/>'
    if tag in src:
        return [f'AndroidManifest.xml :: <queries> already declares {pkg}']
    if '<queries>' not in src:
        raise RuntimeError('manifest has no <queries> block')
    write(p, src.replace('<queries>', '<queries>\n        ' + tag, 1))
    return [f'AndroidManifest.xml :: <queries> + {pkg}']


def class_smali_path(tree, name):
    """Path of the .smali of class `name` (type without L or ;, e.g. 'v8') or None."""
    rel = name + '.smali'
    for d in sorted(os.listdir(tree)):
        if d == 'smali' or d.startswith('smali_classes'):
            p = os.path.join(tree, d, rel)
            if os.path.exists(p):
                return p
    return None


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


# ---------------------------------------------------------------- class-targeted ops
# Used by families whose input is pinned to one exact build (e.g. the alarm fork): the class
# is named explicitly and every edit asserts what it found, so a different build fails loudly
# instead of being patched by a heuristic.


def class_source(tree, cls):
    """(path, source) of class `cls` (e.g. 'w2/k'). Raises RuntimeError if it is missing."""
    path = class_smali_path(tree, cls)
    if not path:
        raise RuntimeError(f'class {cls}: smali not found (did the base change?)')
    return path, read(path)


def op_replace_in_class(tree, cls, old, new, count=1):
    """Replace the exact text `old` with `new` in class `cls`. `old` must occur exactly
    `count` times, otherwise RuntimeError."""
    path, src = class_source(tree, cls)
    found = src.count(old)
    if found != count:
        raise RuntimeError(f'{cls}: expected {count} occurrence(s) of {old[:70]!r} and '
                           f'found {found} (did the base change? check the anchors)')
    write(path, src.replace(old, new))
    return [f'{cls} :: {old.strip().splitlines()[0][:60]} x{count}']


def _class_method_span(src, cls, header):
    """Span of the single method of `src` whose header is exactly `header`."""
    pat = re.compile(r'^\.method ' + re.escape(header) + r'\n', re.M)
    hits = list(pat.finditer(src))
    if len(hits) != 1:
        raise RuntimeError(f'{cls}: expected one method {header!r} and found {len(hits)} '
                           f'(did the base change? check the anchors)')
    end = src.find('\n.end method', hits[0].start())
    return hits[0].start(), end + len('\n.end method')


def op_replace_class_method(tree, cls, header, body_text, must_contain=()):
    """Replace the whole method `header` of class `cls` with `body_text` (everything between
    the header line and `.end method`). The old body must contain every `must_contain`
    string (guards against replacing a different method)."""
    path, src = class_source(tree, cls)
    start, end = _class_method_span(src, cls, header)
    old = src[start:end]
    for needle in must_contain:
        if needle not in old:
            raise RuntimeError(f'{cls}.{header}: old body lacks {needle!r} '
                               f'(did the base change? check the anchors)')
    write(path, src[:start] + '.method ' + header + '\n' + body_text + '\n.end method' +
          src[end:])
    return [f'{cls} :: {header[:60]}']


def op_insert_in_class_method(tree, cls, header, insert):
    """Insert `insert` right after the `.locals` line of method `header` of class `cls`."""
    path, src = class_source(tree, cls)
    start, end = _class_method_span(src, cls, header)
    body = src[start:end]
    m = re.search(r'\n[ ]*\.locals \d+\n', body)
    if not m:
        raise RuntimeError(f'{cls}.{header}: no .locals line')
    body = body[:m.end()] + insert + body[m.end():]
    write(path, src[:start] + body + src[end:])
    return [f'{cls} :: {header[:60]} (+insert)']


def op_remove_smali_dir(tree, rel):
    """Delete the directory `rel` (e.g. 'appfunctions_aggregated_deps') from every smali*
    dir. RuntimeError if it exists in none."""
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


# ---------------------------------------------------------------- manifest / resources


def manifest_edit(tree, fn):
    """Parse AndroidManifest.xml, call fn(root, an) -> list of log entries, write it back.
    `an(name)` expands an attribute name into the android namespace."""
    import xml.etree.ElementTree as ET
    android_ns = 'http://schemas.android.com/apk/res/android'
    ET.register_namespace('android', android_ns)
    path = os.path.join(tree, 'AndroidManifest.xml')
    et = ET.parse(path)
    log = fn(et.getroot(), lambda name: f'{{{android_ns}}}{name}')
    ET.indent(et, space='    ')
    et.write(path, encoding='utf-8', xml_declaration=True)
    return log


def op_add_string_resources(tree, name, values):
    """Add `<string name=...>` to res/values<qualifier>/strings.xml. `values` maps the
    qualifier ('' for default, '-es', ...) to the text. The name must not exist yet in any
    of the values*/strings.xml files and every target file must exist. Text is XML-escaped."""
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
