"""Patch catalogue of the super-patcher, keyed by id.

Every pattern is anchored to strings/structures that survive obfuscation renames (log
strings, semantic method names, pref keys, country lists). Which patterns run, and in
which order, is decided by a profile (profiles/<name>.json), not by this module.
See docs/patches.md.
"""
import os
import re
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                                'scripts', 'lib'))
import config  # noqa: E402

from superpatcher import engine as E  # noqa: E402

SUPPORTED_TYPE = r'CommonConstants\$SupportedType\.smali'
OLD_PKG = 'com.samsung.android.shealthmonitor'
NEW_PKG = 'com.samsung.android.shealthmonitors'
SENSOR_LISTENER = 'Lcom/samsung/android/hardware/sensormanager/SemSensorListener;'
SEM_MANAGER = 'Lcom/samsung/android/hardware/sensormanager/SemSensorManager;'


def _report(paths, log):
    log.extend(paths)
    return paths


# ---------------------------------------------------------------- shared helpers

def _vendor_false_to_true(tree):
    """The exception path (READ_COMPANION_INFO denied in re-signed apps) emits
    Boolean.FALSE; force TRUE."""
    done = []
    for f in E.smali_files(tree):
        src = E.read(f)
        if 'SHWearMonitor-WearCoreServiceRepository' not in src:
            continue
        if 'Boolean;->FALSE:Ljava/lang/Boolean;' in src:
            src = src.replace('Ljava/lang/Boolean;->FALSE:Ljava/lang/Boolean;',
                              'Ljava/lang/Boolean;->TRUE:Ljava/lang/Boolean;')
            E.write(f, src)
            done.append(os.path.relpath(f, tree) + ' :: FALSE emit -> TRUE')
    return done


def _rebrand(tree, extra_auth=()):
    """package -> com.samsung.android.shealthmonitors: manifest (package, permissions,
    authorities) plus exact package strings in code (PM lookups, tile package, ...)."""
    done = E.op_manifest_rebrand(tree, OLD_PKG, NEW_PKG)
    done += [p + ' [pkg]' for p in E.op_replace_const_string_exact(tree, OLD_PKG, NEW_PKG)]
    for sub in extra_auth:
        done += [p + ' [auth]' for p in E.op_replace_const_string_exact(
            tree, OLD_PKG + sub, NEW_PKG + sub)]
    return done


def _watch_rebrand(tree):
    return _rebrand(tree, ('.sleep', '.bp'))


def _phone_rebrand(tree):
    # Same package as the watch app: the GMS Data Layer only delivers between equal AppKeys.
    done = _rebrand(tree, ('.sleep', '.bp', '.ecg', '.fileprovider', '.pdf.fileprovider'))
    # "Share" (ShareViaUtils) builds the URI by hand with the old authority.
    done += [p + ' [auth-uri]' for p in E.op_replace_const_string_exact(
        tree, f'content://{OLD_PKG}.fileprovider/', f'content://{NEW_PKG}.fileprovider/')]
    return done


def _region_supported(tree):
    """Method returning CommonConstants$SupportedType -> sget .a + return. Excludes the
    enum boilerplate (valueOf/values) and requires a region anchor."""
    done = []
    for f in E.smali_files(tree):
        if '/shealthmonitor/util/' not in f:
            continue
        src = E.read(f)
        if 'MCC_NOT_SUPPORT' not in src and 'CountryDefine' not in src:
            continue
        for m in re.finditer(r'\.method\s+([^\n]*\)Lcom/samsung/android/shealthmonitor/util/'
                             r'CommonConstants\$SupportedType;)', src):
            sig = m.group(1)
            if 'valueOf' in sig or 'values' in sig:
                continue
            insert = ('\n    sget-object v0, Lcom/samsung/android/shealthmonitor/util/'
                      'CommonConstants$SupportedType;->a:'
                      'Lcom/samsung/android/shealthmonitor/util/CommonConstants$SupportedType;\n\n'
                      '    return-object v0\n')
            src2, ok = E.insert_at_method_start(src, re.escape(sig), insert, need_regs=1)
            if ok:
                src = src2
                done.append(os.path.relpath(f, tree) + ' :: ' + sig[:50])
        E.write(f, src)
    return done


def _neutralize_root_check(tree):
    """Class with 'S HealthMonitor - PackageValidator': the method with the string -> false,
    plus any other )Z method validating certificates ([[B) -> false."""
    done = []
    for f in E.smali_files(tree):
        src = E.read(f)
        if 'S HealthMonitor - PackageValidator' not in src:
            continue
        rel = os.path.relpath(f, tree)
        for m in re.finditer(r'\.method\s+([^\n]+)\n(.*?)(?=\n\.end method)', src, re.S):
            if 'PackageValidator' in m.group(2) and re.search(r'\)Z', m.group(1)):
                sig = m.group(1)
                src2, ok = E.insert_at_method_start(src, re.escape(sig),
                                                    '\n    const/4 v0, 0x0\n\n    return v0\n')
                if ok:
                    src = src2
                    done.append(f'{rel} :: {sig[:50]} -> false')
        for m in re.finditer(r'\.method\s+([^\n]+)\n(.*?)(?=\n\.end method)', src, re.S):
            body = m.group(2)
            if '[[B' in body and re.search(r'\)Z', m.group(1)) and 'const/4 v0, 0x0' not in body[:80]:
                sig = m.group(1)
                src2, ok = E.insert_at_method_start(src, re.escape(sig),
                                                    '\n    const/4 v0, 0x0\n\n    return v0\n')
                if ok:
                    src = src2
                    done.append(f'{rel} :: {sig[:50]} -> false')
        E.write(f, src)
    return done


def _main_gates(tree):
    done = []
    glob = r'/SHealthMonitorMainActivity\.smali$'
    done += [p + ' [void]' for p in E.op_return_const(
        tree, file_glob=glob, sig_regex=r'private final checkSystem\(\)V', kind='void')]
    done += [p + ' [void]' for p in E.op_return_const(
        tree, file_glob=glob, sig_regex=r'private final updateVersionCheck\(\)V', kind='void')]
    done += [p + ' [singleton]' for p in E.op_return_singleton(
        tree, glob, r'private final isScreenLockEnabled\(\)L[^;\n]+;',
        'sget-object v0, {type}->a:{type}')]
    return done


# ---------------------------------------------------------------- watch-only helpers

def _watch_ecg_sensor(tree):
    """SensorManager wrapper of the watch app (anchored on the SemSensorManager calls):
    sensor type 0x1b (ECG) is always "available" and is registered/unregistered through
    EcgAdapter (Samsung Health Tracking SDK) instead of the privileged SSENSOR HAL;
    sensor types 0x25 (IHRN) and 0x31 (SpO2/apnea) get an availability exemption
    (WatchOverrides.sensorAvailability)."""
    ecg = config.smali_type('ecg/EcgAdapter')
    wo = config.smali_type('watch/WatchOverrides')
    ecg_const = ('    const/16 v0, 0x1b\n\n    if-ne {reg}, v0, :{lbl}\n\n')
    done = []
    # isAvailable(I)Z: v2 keeps the sensor type (p1 is reused as a String in the catch)
    done += E.op_insert_in_method(
        tree, r'public final \w+\(I\)Z',
        '\n    move v2, p1\n\n' + ecg_const.format(reg='p1', lbl='ss_skip_a')
        + '    const/4 v0, 0x1\n\n    return v0\n\n    :ss_skip_a\n',
        body_all=(SEM_MANAGER + '->isAvailable(I)Z',), need_regs=3,
        tail=f'    invoke-static {{v2, p0}}, {wo}->sensorAvailability(IZ)Z\n\n'
             '    move-result p0\n\n',
        label='sensor wrapper isAvailable')
    # isFeatureSupported(ILjava/lang/String;)Z
    done += E.op_insert_in_method(
        tree, r'public final \w+\(ILjava/lang/String;\)Z',
        '\n' + ecg_const.format(reg='p1', lbl='ss_skip_b')
        + '    const/4 v0, 0x1\n\n    return v0\n\n    :ss_skip_b\n',
        body_all=(SEM_MANAGER + '->isFeatureSupported(',), label='sensor wrapper isFeatureSupported')
    # registerListener(Listener, I)Z
    done += E.op_insert_in_method(
        tree, r'public final \w+\(' + re.escape(SENSOR_LISTENER) + r'I\)Z',
        '\n' + ecg_const.format(reg='p2', lbl='ss_skip_c')
        + f'    invoke-static {{p1, p2}}, {ecg}->start({SENSOR_LISTENER}I)Z\n\n'
        '    move-result v0\n\n    return v0\n\n    :ss_skip_c\n',
        body_all=(SEM_MANAGER + '->registerListener(',), label='sensor wrapper registerListener')
    # unregisterListener(Listener, I)V
    done += E.op_insert_in_method(
        tree, r'public final \w+\(' + re.escape(SENSOR_LISTENER) + r'I\)V',
        '\n' + ecg_const.format(reg='p2', lbl='ss_skip_e')
        + f'    invoke-static {{p1, p2}}, {ecg}->stop({SENSOR_LISTENER}I)V\n\n'
        '    return-void\n\n    :ss_skip_e\n',
        body_all=(SEM_MANAGER + '->unregisterListener(',), label='sensor wrapper unregisterListener')
    return done


def _watch_apnea_country(tree):
    """CSC utils: the apnea "supported country" check returns true up front (regional
    check disabled; the original compares against a fixed country list). Anchored on the
    log string of the method."""
    insert = ('\n    const-string v0, "SHWearMonitor-CSCUtils"\n\n'
              '    const-string v1, "isApneaSupportCountry : regional check disabled (vendor override)"\n\n'
              '    invoke-static {v0, v1}, Landroid/util/Log;->i(Ljava/lang/String;Ljava/lang/String;)I\n\n'
              '    const/4 v0, 0x1\n\n    return v0\n')
    return E.op_insert_in_method(
        tree, r'public static \w+\(\)Z', insert,
        body_all=('"isApneaSupportCountry : "', '"SHWearMonitor-CSCUtils"'),
        need_regs=2, label='apnea country check')


def _watch_apnea_version_reeval(tree):
    """ApneaVersionManager: a persisted NOT_SUPPORTED (written by a build that still had the
    regional check) is re-evaluated instead of being returned as sticky. Anchored on the log
    string 'Stored device version : '; the enum field of NOT_SUPPORTED is read from the enum."""
    found = E.find_methods(tree, r'public static \w+\(\)L\w+;',
                           body_all=('"Stored device version : "',), label='apnea stored version')
    done = []
    for f, sig in found:
        src = E.read(f)
        span = E.method_span(src, re.escape(sig))
        body = src[span[0]:span[1]]
        m = re.search(r'sget-object (v\d), (L\w+;)->\w+:\2\n\n    if-eq (v\d), \1, :(\w+)\n'
                      r'\n    return-object \3\n', body)
        if not m:
            raise RuntimeError('apnea stored version: "return stored value" structure not found')
        reg, etype, stored, label = m.group(1), m.group(2), m.group(3), m.group(4)
        field = E.enum_field_for_name(tree, etype, 'NOT_SUPPORTED')
        guard = (f'    sget-object {reg}, {etype}->{field}:{etype}\n\n'
                 f'    if-eq {stored}, {reg}, :{label}\n\n')
        if guard in body:
            done.append(os.path.relpath(f, tree) + ' :: already applied')
            continue
        at = m.start() + body[m.start():].index('    return-object ' + stored)
        body = body[:at] + guard + body[at:]
        E.write(f, src[:span[0]] + body + src[span[1]:])
        done.append(os.path.relpath(f, tree) + f' :: NOT_SUPPORTED ({etype}->{field}) re-evaluated')
    return done


def _watch_mobile_installed(tree):
    """Mobile-app-installed capability getter ('application_installed_in_mobile' pref):
    always true, so the ECG capability gate opens."""
    return E.op_set_method_body(
        tree, r'public static \w+\(\)Z',
        '    .locals 1\n\n    # PATCH: always report the phone app as installed (capability_ecg gate)\n'
        '    const/4 v0, 0x1\n\n    return v0',
        body_all=('"application_installed_in_mobile"', 'Ljava/lang/Boolean;->FALSE:'),
        label='mobile-installed getter')


def _watch_capability_gates(tree):
    return E.op_force_invoke_result(
        tree, file_anchor='SHWearMonitor-WearCoreServiceRepository',
        invoke_regex=r'L\w+;->\w+\(Ljava/lang/String;Ljava/lang/String;Z\)Z',
        preceding_const='"samsung"', value='0x1') + _vendor_false_to_true(tree)


def _watch_alarm_system_app(tree):
    # setExactAlarmForSystemApp needs a system app; a re-signed one is not, and the
    # SecurityException kills the process from the IHRN off-body detector. A non-existent
    # name -> NoSuchMethodException -> stock setAlarmClock path (SCHEDULE_EXACT_ALARM).
    return E.op_replace_const_string_exact(
        tree, 'setExactAlarmForSystemApp', 'setExactAlarmForSystemApp__not_system_app')


# ---------------------------------------------------------------- catalogue

PATTERNS = {}


def _add(pid, families, desc, run):
    PATTERNS[pid] = {'id': pid, 'families': families, 'desc': desc, 'run': run}


def _sweep(const, repl, invokes=None, exclude=()):
    kw = {'invokes': invokes} if invokes else {}
    return lambda tree, log: _report(E.sweep_string_compare_auto_true(
        tree, const, replace_value=repl, exclude_globs=exclude, **kw), log)


# generic
_add('generic-manufacturer-check', '*',
     'Manufacturer check "samsung".contains/equals -> auto-true',
     _sweep('samsung', 'android',
            exclude=(r'/(androidx|com/google|com/bumptech|android/support)/',)))
_add('generic-vendor-check', '*',
     'SsdkVendorCheck: "Samsung" -> "Android" (compare auto-true)',
     _sweep('Samsung', 'Android',
            invokes=((r'Ljava/lang/String;->compareToIgnoreCase\(Ljava/lang/String;\)I',
                      'Ljava/lang/String;->compareToIgnoreCase(Ljava/lang/String;)I'),)))

# SHM phone (and any SHM)
# TODO(owner): document the personal-use justification for this pattern.
_add('shm-min-age-22-to-16', ('shm-phone', 'shm-watch'),
     'MIN_AGE 0x16 (22) -> 0x10 (16) in util/c',
     lambda tree, log: _report(E.op_swap_const(
         tree, r'/shealthmonitor/util/c\.smali$', 'const/16 v0, 0x16', 'const/16 v0, 0x10',
         context='shealthmonitor/util/c'), log))
_add('shm-supported-type-ordinals', ('shm-phone',),
     'MCC/CSC/ALL/NO_SIM ordinals -> 0 (rejection = supported)',
     lambda tree, log: _report(E.op_enum_ordinals_zero(
         tree, SUPPORTED_TYPE, ['0x1', '0x2', '0x3', '0x4']), log))
_add('shm-region-supportedtype', ('shm-phone',),
     'Region check returning SupportedType -> always .a (supported)',
     lambda tree, log: _report(_region_supported(tree), log))
_add('shm-region-countrylist', ('shm-phone',),
     'Boolean country check ("BH" in list) -> true',
     lambda tree, log: _report(E.op_return_const(
         tree, sig_regex=r'[\s\S]*?\)Z', value='0x1', body_anchor='"BH"',
         file_glob=r'/shealthmonitor/util/'), log))
# Root cause: the app re-signed for personal use no longer matches the Samsung
# certificate, so the PackageValidator is neutralised (owner's own device).
_add('shm-root-packagevalidator', ('shm-phone',),
     'PackageValidator/root: validation methods -> false',
     lambda tree, log: _report(_neutralize_root_check(tree), log))
_ARC = r'/version/api/AppVersionCheckResponse\.smali$'
_add('shm-force-update-response', ('shm-phone',),
     'AppVersionCheckResponse: failed/updateNeeded -> false; non-null version (the watch '
     'response can come back failed without versionName and the NPE kills the process '
     'mid-handshake)',
     lambda tree, log: _report(
         E.op_return_const(tree, file_glob=_ARC,
                           sig_regex=r'(public|private)[^\n]*is(ResponseFailed|UpdateNeeded)\(\)Z',
                           value='0x0') +
         E.op_return_const(tree, file_glob=_ARC,
                           sig_regex=r'public[^\n]*getVersionName\(\)Ljava/lang/String;',
                           kind='object', value='const-string v0, "1.5.1.022"') +
         E.op_return_const(tree, file_glob=_ARC,
                           sig_regex=r'public[^\n]*getVersionCode\(\)Ljava/lang/String;',
                           kind='object', value='const-string v0, "1501022"'), log))
_add('shm-force-update-pref', ('shm-phone',),
     'Method reading the required-version pref -> false',
     lambda tree, log: _report(E.op_return_const(
         tree, sig_regex=r'[\s\S]*?\)Z',
         body_anchor='shealth_monitor_base_app_update_latest_version_code', value='0x0'), log))
_add('shm-main-gates', ('shm-phone',),
     'checkSystem/updateVersionCheck -> void; isScreenLockEnabled -> success',
     lambda tree, log: _report(_main_gates(tree), log))
_add('shm-setup-network-gate', ('shm-phone',),
     'checkNoNetworkError -> void (no SIM/region gate in setup)',
     lambda tree, log: _report(E.op_return_const(
         tree, file_glob=r'/SHealthMonitorSetupActivity\.smali$',
         sig_regex=r'private [^\n]*checkNoNetworkError\(\)V', kind='void'), log))
_add('shm-sleep-apnea-support', ('shm-phone',),
     'isPhoneSupportSleepApnea -> true (const after move-result)',
     lambda tree, log: _report(E.op_force_const_after_move(
         tree, '"[isPhoneSupportSleepApnea] isSleepApneaModel = "', '0x1'), log))
_add('phone-rebrand-gms', ('shm-phone',),
     'Phone: package -> com.samsung.android.shealthmonitors (same package and certificate as '
     'the watch app: the GMS Data Layer only delivers between equal AppKeys)',
     lambda tree, log: _report(_phone_rebrand(tree), log))

# SHM watch
_add('watch-rebrand-coexist', ('shm-watch',),
     'Watch: package -> shealthmonitors (manifest, permissions, authorities, tile package) '
     'so it coexists with the system app',
     lambda tree, log: _report(_watch_rebrand(tree), log))
_add('watch-queries-health-service', ('shm-watch',),
     'Watch: manifest <queries> declares com.samsung.android.service.health (package '
     'visibility for the Health Tracking Service)',
     lambda tree, log: _report(E.op_manifest_add_query_package(
         tree, 'com.samsung.android.service.health'), log))
_add('watch-companion-vendor', ('shm-watch',),
     'Watch: accept any companion vendor ("samsung" match forced; READ_COMPANION_INFO '
     'denial path -> TRUE)',
     lambda tree, log: _report(_watch_capability_gates(tree), log))
_add('watch-alarm-system-app', ('shm-watch',),
     'Watch: setExactAlarmForSystemApp lookup renamed -> stock setAlarmClock path',
     lambda tree, log: _report(_watch_alarm_system_app(tree), log))
_add('watch-mobile-installed', ('shm-watch',),
     'Watch: "phone app installed" getter -> true (opens the ECG capability gate)',
     lambda tree, log: _report(_watch_mobile_installed(tree), log))
_add('watch-ecg-sensor-adapter', ('shm-watch',),
     'Watch: ECG sensor (0x1b) via EcgAdapter (Health Tracking SDK) + availability '
     'exemption for IHRN (0x25) and SpO2/apnea (0x31)',
     lambda tree, log: _report(_watch_ecg_sensor(tree), log))
_add('watch-apnea-country', ('shm-watch',),
     'Watch: apnea country check -> true (regional check disabled)',
     lambda tree, log: _report(_watch_apnea_country(tree), log))
_add('watch-apnea-reeval-not-supported', ('shm-watch',),
     'Watch: persisted NOT_SUPPORTED apnea version is re-evaluated',
     lambda tree, log: _report(_watch_apnea_version_reeval(tree), log))


# Alarm app fork (AlarmWatch 1.3.05.9, pinned by apks/SHA256SUMS). The input is one exact
# build, so classes and methods are named explicitly (obfuscated names of THAT build) and
# every edit asserts what it found: another build makes the pattern fail, never a no-op.
ALARM_PKG = 'com.samsung.android.watch.alarm'
ALARM_SERVICE = 'com/samsung/android/watch/alarm/viewmodel/CAlarmService'
ALARM_MAIN_ACTIVITY = ALARM_PKG + '.activity.AlarmMainActivity'
ALARM_LABEL_NAME = 'interop_app_label'
ALARM_LABEL = {'': 'Phone alarms', '-es': 'Alarmas del teléfono'}  # user-facing text
FORK_PKG = config.java_name('shmbridge.alarm')
FORK_PERM = config.java_name('shmbridge.permission.ALARM_LINK')
FORK_ACTION_IN = config.java_name('shmbridge.alarm.IN')
FORK_LINK = config.smali_type('shmbridge/alarm/Link')


def _simple(name):
    return name.rsplit('.', 1)[-1]


def _expect(found, wanted, what):
    """Raise unless the two collections hold the same items (each exactly once)."""
    found = sorted(found)
    if found != sorted(wanted):
        raise RuntimeError(f'{what}: expected {sorted(wanted)} and matched {found} '
                           f'(did the base change? check the anchors)')


def _alarm_app(root):
    app = root.find('application')
    if app is None:
        raise RuntimeError('manifest has no <application>')
    return app


def _alarm_symbols(tree):
    """The classes/members the injected Link reflects on still exist (obfuscated names of
    AlarmWatch 1.3.05.9)."""
    checks = (
        ('o2/b', '.method public static a(Ljava/lang/String;)'),
        ('o2/c', '.method public final f()'),
        ('k0/b', '.method public static a(Landroid/content/Context;)'),
        ('k0/b', '.method public final c(Landroid/content/Intent;)V'),
        (ALARM_PKG.replace('.', '/') + '/service/AlarmSyncService', ' m:J'),
    )
    done = []
    for cls, needle in checks:
        _path, src = E.class_source(tree, cls)
        if needle not in src:
            raise RuntimeError(f'{cls}: missing {needle!r} (Link depends on it)')
        done.append(f'{cls} :: {needle.strip()}')
    return done


def _alarm_rebrand_manifest(tree):
    def edit(root, an):
        log = []
        if root.get('package') != ALARM_PKG:
            raise RuntimeError(f'manifest package is {root.get("package")!r}')
        root.set('package', FORK_PKG)
        log.append(f'manifest package {ALARM_PKG} -> {FORK_PKG}')
        dyn = ALARM_PKG + '.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'
        for tag in ('permission', 'uses-permission'):
            els = [e for e in root.findall(tag) if e.get(an('name')) == dyn]
            _expect([e.get(an('name')) for e in els], [dyn], f'<{tag}> {dyn}')
            els[0].set(an('name'), FORK_PKG + '.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION')
            log.append(f'<{tag}> {dyn} renamed')
        app = _alarm_app(root)
        auth = {ALARM_PKG: FORK_PKG,
                ALARM_PKG + '.androidx-startup': FORK_PKG + '.androidx-startup'}
        provs = [p for p in app.findall('provider') if p.get(an('authorities')) in auth]
        _expect([p.get(an('authorities')) for p in provs], list(auth), 'provider authorities')
        for p in provs:
            old = p.get(an('authorities'))
            p.set(an('authorities'), auth[old])
            log.append(f'provider authority {old} -> {auth[old]}')
        return log
    return E.manifest_edit(tree, edit)


def _alarm_rebrand_code(tree):
    done = E.op_replace_in_class(
        tree, 'u2/b', f'"content://{ALARM_PKG}/alarm"', f'"content://{FORK_PKG}/alarm"')
    quoted = f'"{ALARM_PKG}"'
    # exact quoted package string only: action names such as "<pkg>.C_ACTION_*" are untouched
    for cls, n in (('c0/c', 3), ('J0/e', 1), ('e2/i', 2), ('Z1/b', 1)):
        done += E.op_replace_in_class(tree, cls, quoted, f'"{FORK_PKG}"', count=n)
    return done


_ALARM_REMOVE_PERMS = (
    'android.permission.START_ACTIVITIES_FROM_BACKGROUND',
    'android.permission.DEVICE_POWER',
    'com.samsung.android.scloud.sync.permission.READ',
    'com.samsung.android.scloud.sync.permission.WRITE',
    'com.samsung.android.permisson.ACCESS_SYSTEM_KEY_DISPATCHING',
    'android.permission.BLUETOOTH',
    'android.permission.LOCAL_MAC_ADDRESS',
    'android.permission.STOP_APP_SWITCHES',
    'com.samsung.android.wear.retailmode.permission.RETAILMODE',
    'android.permission.READ_PRIVILEGED_PHONE_STATE',
    'com.samsung.wear.permission.SHOW_TILE_TOAST_PRIVILEGED',
    'android.permission.REAL_GET_TASKS',
    'com.samsung.accessibility.permission.REGISTER_UNIVERSAL_GESTURE_ACTION',
    'com.samsung.permission.SSENSOR',
    'com.samsung.android.clockwork.settings.permission.CHANGE_TOUCH_LOCK',
)
# Permissions declared by the original app (all but the dynamic-receiver one are dropped).
_ALARM_REMOVE_DECLS = ('BIND_COMPLICATION_PROVIDER', 'permission.READ_ALARM',
                       'permission.WRITE_ALARM', 'permission.EDIT_ALARM')


def _alarm_permissions(tree):
    def edit(root, an):
        log = []
        uses = [e for e in root.findall('uses-permission')
                if e.get(an('name')) in _ALARM_REMOVE_PERMS]
        _expect([e.get(an('name')) for e in uses], _ALARM_REMOVE_PERMS,
                '<uses-permission> removal')
        for e in uses:
            root.remove(e)
        log.append(f'{len(uses)} privileged/system <uses-permission> removed')
        decls = [e for e in root.findall('permission')
                 if not e.get(an('name')).endswith('.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION')]
        _expect([e.get(an('name')) for e in decls],
                [ALARM_PKG + '.' + d for d in _ALARM_REMOVE_DECLS], '<permission> declarations')
        for e in decls:
            root.remove(e)
        log.append(f'{len(decls)} <permission> declarations removed')
        return log
    return E.manifest_edit(tree, edit)


def _alarm_notification_permissions(tree):
    def edit(root, an):
        import xml.etree.ElementTree as ET
        names = ('android.permission.POST_NOTIFICATIONS',
                 'android.permission.USE_FULL_SCREEN_INTENT')
        have = {e.get(an('name')) for e in root.findall('uses-permission')}
        if have & set(names):
            raise RuntimeError('notification permissions already declared')
        for n in names:
            ET.SubElement(root, 'uses-permission', {an('name'): n})
        return [f'<uses-permission> + {n}' for n in names]
    return E.manifest_edit(tree, edit)


def _alarm_queries_and_libs(tree):
    def edit(root, an):
        log = []
        queries = root.findall('queries')
        _expect([q.tag for q in queries], ['queries'], '<queries>')
        root.remove(queries[0])
        log.append('<queries> removed')
        app = _alarm_app(root)
        gone = ('wear-sdk', 'androidx.window.extensions', 'androidx.window.sidecar',
                'com.android.extensions.appfunctions')
        libs = [e for e in app.findall('uses-library') if e.get(an('name')) in gone]
        _expect([e.get(an('name')) for e in libs], gone, '<uses-library> removal')
        for e in libs:
            app.remove(e)
        log.append(f'{len(libs)} <uses-library> removed')
        wear = [e for e in app.findall('uses-library')
                if e.get(an('name')) == 'com.google.android.wearable']
        _expect([e.get(an('name')) for e in wear], ['com.google.android.wearable'],
                'wearable library')
        wear[0].set(an('required'), 'false')
        log.append('com.google.android.wearable required=false')
        return log
    return E.manifest_edit(tree, edit)


_ALARM_REMOVE_COMPONENTS = (
    'ClientProvider', 'RetailModeReceiver', 'DetachModeReceiver', 'AlarmBackupRestoreReceiver',
    'AlarmSyncService', 'AlarmTileService2x1', 'AlarmTileService',
    'AlarmComplicationMessengerService', 'AlarmComplicationProviderService',
    'AlarmEditService', 'AlarmCTSHandleActivity', 'CapsuleProvider', 'ProfileInstallReceiver',
    'GoogleApiActivity', 'MainAppFunctionService', 'MultiInstanceInvalidationService',
)
_ALARM_REMOVE_META = (
    'com.google.android.wearable.standalone', 'com.samsung.android.sdk.bixby2.companionApp',
    'wear-oem-home-app-path-prefixes', 'backup_name', 'backup_content_uri',
    'com.google.android.gms.version', 'com.samsung.android.sdk.bixby2.version',
    'SPDE.build.signature', 'SPDE.env.version',
)


def _alarm_components(tree):
    def edit(root, an):
        app = _alarm_app(root)
        comps = [e for tag in ('activity', 'service', 'receiver', 'provider')
                 for e in app.findall(tag)
                 if _simple(e.get(an('name'))) in _ALARM_REMOVE_COMPONENTS]
        _expect([_simple(e.get(an('name'))) for e in comps], _ALARM_REMOVE_COMPONENTS,
                'components')
        for e in comps:
            app.remove(e)
        metas = [e for e in app.findall('meta-data') if e.get(an('name')) in _ALARM_REMOVE_META]
        _expect([e.get(an('name')) for e in metas], _ALARM_REMOVE_META,
                'application <meta-data>')
        for e in metas:
            app.remove(e)
        return [f'{len(comps)} components removed (GMS/tile/complication/backup/bixby/'
                'appfunctions)', f'{len(metas)} application <meta-data> removed']
    return E.manifest_edit(tree, edit)


def _alarm_provider_open(tree):
    prov_name = ALARM_PKG + '.provider.AlarmProvider'

    def edit(root, an):
        provs = [p for p in _alarm_app(root).findall('provider')
                 if p.get(an('name')) == prov_name]
        _expect([p.get(an('name')) for p in provs], [prov_name], 'AlarmProvider')
        for attr in ('readPermission', 'writePermission'):
            if provs[0].attrib.pop(an(attr), None) is None:
                raise RuntimeError(f'AlarmProvider has no {attr}')
        return ['AlarmProvider readPermission/writePermission removed']
    return E.manifest_edit(tree, edit)


def _alarm_link_components(tree):
    def edit(root, an):
        import xml.etree.ElementTree as ET
        app = _alarm_app(root)
        existing = {e.get(an('name')) for e in root.iter() if e.get(an('name'))}
        for n in (FORK_PERM, FORK_PKG + '.LinkActivity', FORK_PKG + '.LinkReceiver'):
            if n in existing:
                raise RuntimeError(f'{n} already present')
        perm = ET.SubElement(root, 'permission')
        perm.set(an('name'), FORK_PERM)
        perm.set(an('protectionLevel'), 'signature')
        ET.SubElement(root, 'uses-permission', {an('name'): FORK_PERM})
        act = ET.SubElement(app, 'activity')
        act.set(an('name'), FORK_PKG + '.LinkActivity')
        act.set(an('exported'), 'true')
        act.set(an('theme'), '@android:style/Theme.NoDisplay')
        act.set(an('excludeFromRecents'), 'true')
        act.set(an('permission'), FORK_PERM)
        rcv = ET.SubElement(app, 'receiver')
        rcv.set(an('name'), FORK_PKG + '.LinkReceiver')
        rcv.set(an('exported'), 'true')
        rcv.set(an('permission'), FORK_PERM)
        flt = ET.SubElement(rcv, 'intent-filter')
        ET.SubElement(flt, 'action').set(an('name'), FORK_ACTION_IN)
        return [f'signature permission {FORK_PERM}',
                'LinkActivity (NoDisplay, permission-protected)',
                f'LinkReceiver (action {FORK_ACTION_IN}, permission-protected)']
    return E.manifest_edit(tree, edit)


# Exact smali of the 1.3.05.9 build.
_ALARM_GMS_OBSERVER = '\n\n'.join((
    '    new-instance v0, Lw2/n;',
    '    const/4 v1, 0x0',
    '    invoke-direct {v0, p0, v1}, Lw2/n;-><init>(L' + ALARM_SERVICE + ';LI2/d;)V',
    '    const/4 v2, 0x3',
    '    iget-object v3, p0, L' + ALARM_SERVICE + ';->y:Le3/e;',
    '    invoke-static {v3, v1, v0, v2}, LZ2/x;->m(LZ2/u;La3/d;LR2/p;I)LZ2/m0;',
    '    move-result-object v0',
    '    iput-object v0, p0, L' + ALARM_SERVICE + ';->z:LZ2/m0;',
)) + '\n\n'

_ALARM_FORCE_SOUND_VIBRATE = """    .locals 2

    invoke-virtual {p0}, Lw2/k;->e()Lw2/f;

    move-result-object v0

    if-eqz v0, :skip

    const/16 v1, 0x11

    invoke-virtual {v0, v1}, Lw2/f;->e(I)V

    :skip
    return-void"""

_ALARM_SEND_BODY = f"""    .locals 0
    invoke-static {{p0, p1, p2}}, {FORK_LINK}->send(Landroid/content/Context;Ljava/lang/String;Ljava/lang/String;)V
    return-void"""


def _alarm_semgesture(tree):
    g = 'Lcom/samsung/android/hardware/gesturemanager/SemGestureManager;'
    done = E.op_replace_in_class(
        tree, 'w2/k',
        f'    iget-object v7, v1, Lw2/k;->o:{g}\n\n    if-nez v7, :cond_8\n\n'
        '    const-string v7, "sem_gesture"\n',
        f'    iget-object v7, v1, Lw2/k;->o:{g}\n\n    goto :cond_8\n\n'
        '    const-string v7, "sem_gesture"\n')
    done += E.op_replace_in_class(
        tree, 'w2/k',
        f'    if-eqz v7, :cond_1c\n\n    invoke-virtual {{v7, v6}}, {g}->pauseGesture(Z)V\n',
        f'    if-eqz v7, :interop_no_gesture\n\n'
        f'    invoke-virtual {{v7, v6}}, {g}->pauseGesture(Z)V\n\n    :interop_no_gesture\n')
    done += E.op_replace_in_class(
        tree, 'w2/j',
        '    if-ne p1, v3, :cond_5\n\n    const-string p1, "sem_gesture"\n',
        '    goto :cond_5\n\n    const-string p1, "sem_gesture"\n')
    return done


def _alarm_hide_launcher(tree):
    def edit(root, an):
        act = [a for a in _alarm_app(root).findall('activity')
               if a.get(an('name')) == ALARM_MAIN_ACTIVITY]
        _expect([a.get(an('name')) for a in act], [ALARM_MAIN_ACTIVITY], 'launcher activity')
        hits = [f for f in act[0].findall('intent-filter')
                if any(c.get(an('name')) == 'android.intent.category.LAUNCHER'
                       for c in f.findall('category'))
                and any(a.get(an('name')) == 'android.intent.action.MAIN'
                        for a in f.findall('action'))]
        if len(hits) != 1:
            raise RuntimeError(f'expected one MAIN/LAUNCHER intent-filter, found {len(hits)}')
        act[0].remove(hits[0])
        return [f'{_simple(ALARM_MAIN_ACTIVITY)}: MAIN/LAUNCHER intent-filter removed '
                '(activity kept)']
    return E.manifest_edit(tree, edit)


def _alarm_relabel(tree):
    done = E.op_add_string_resources(tree, ALARM_LABEL_NAME, ALARM_LABEL)
    ref = '@string/' + ALARM_LABEL_NAME

    def edit(root, an):
        app = _alarm_app(root)
        if app.get(an('label')) != '@string/app_name':
            raise RuntimeError(f'application label is {app.get(an("label"))!r}')
        app.set(an('label'), ref)
        act = [a for a in app.findall('activity') if a.get(an('name')) == ALARM_MAIN_ACTIVITY]
        _expect([a.get(an('name')) for a in act], [ALARM_MAIN_ACTIVITY], 'launcher activity')
        if act[0].get(an('label')) != '@string/app_name':
            raise RuntimeError(f'launcher activity label is {act[0].get(an("label"))!r}')
        act[0].set(an('label'), ref)
        return [f'application and launcher activity label -> {ref}']
    return done + E.manifest_edit(tree, edit)


# Alarm fork patterns, in profile order (profiles/alarm.json).
_AF = ('alarm-watch',)
_add('alarm-verify-symbols', _AF,
     'Link dependencies (o2.b/o2.c JSON model, k0.b local broadcast, AlarmSyncService.m) exist',
     lambda tree, log: _report(_alarm_symbols(tree), log))
_add('alarm-rebrand-manifest', _AF,
     'Manifest: package, dynamic-receiver permission and provider authorities -> fork package',
     lambda tree, log: _report(_alarm_rebrand_manifest(tree), log))
_add('alarm-rebrand-code', _AF,
     'Smali: content URI (u2/b) and exact package strings (c0/c, J0/e, e2/i, Z1/b) -> fork '
     'package',
     lambda tree, log: _report(_alarm_rebrand_code(tree), log))
_add('alarm-permissions-prune', _AF,
     'Manifest: drop privileged/system uses-permission and the vendor permission declarations',
     lambda tree, log: _report(_alarm_permissions(tree), log))
_add('alarm-notification-permissions', _AF,
     'Manifest: add POST_NOTIFICATIONS and USE_FULL_SCREEN_INTENT',
     lambda tree, log: _report(_alarm_notification_permissions(tree), log))
_add('alarm-queries-libs', _AF,
     'Manifest: drop <queries> and the wear-sdk/window/appfunctions libs; wearable lib optional',
     lambda tree, log: _report(_alarm_queries_and_libs(tree), log))
_add('alarm-components-prune', _AF,
     'Manifest: drop GMS/tile/complication/backup/bixby/appfunctions components and meta-data',
     lambda tree, log: _report(_alarm_components(tree), log))
_add('alarm-provider-open', _AF,
     'Manifest: AlarmProvider without the vendor read/write permissions',
     lambda tree, log: _report(_alarm_provider_open(tree), log))
_add('alarm-link-components', _AF,
     'Manifest: ALARM_LINK signature permission, LinkActivity, LinkReceiver (action IN)',
     lambda tree, log: _report(_alarm_link_components(tree), log))
_add('alarm-appfunctions-drop', _AF,
     'Smali: remove the appfunctions_aggregated_deps tree (its component is gone)',
     lambda tree, log: _report(E.op_remove_smali_dir(tree, 'appfunctions_aggregated_deps'), log))
_add('alarm-semgesture-skip', _AF,
     'SemGestureManager: skip the sem_gesture lookup (w2/k, w2/j) and the pauseGesture null path',
     lambda tree, log: _report(_alarm_semgesture(tree), log))
_add('alarm-gms-observer-drop', _AF,
     'CAlarmService.onCreate: remove the GMS connection observer (it stops the service early)',
     lambda tree, log: _report(E.op_replace_in_class(
         tree, ALARM_SERVICE, _ALARM_GMS_OBSERVER, ''), log))
_add('alarm-service-stop-on-destroy', _AF,
     'w2/k.onDestroy: call t() first so sound/vibration is never orphaned',
     lambda tree, log: _report(E.op_insert_in_class_method(
         tree, 'w2/k', 'public onDestroy()V', '\n    invoke-virtual {p0}, Lw2/k;->t()V\n'), log))
_add('alarm-force-sound-vibrate', _AF,
     'CAlarmService.r(): play mode always 0x11 (sound+vibrate), ignoring the ringer mode',
     lambda tree, log: _report(E.op_replace_class_method(
         tree, ALARM_SERVICE, 'public final r()V', _ALARM_FORCE_SOUND_VIBRATE,
         must_contain=('semGetRingerModeInternal',)), log))
_add('alarm-link-send', _AF,
     'o2/b.e() (sendMessageToAll): body replaced by Link.send(context, path, data)',
     lambda tree, log: _report(E.op_replace_class_method(
         tree, 'o2/b',
         'public static e(Landroid/content/Context;Ljava/lang/String;Ljava/lang/String;LC1/c;)V',
         _ALARM_SEND_BODY, must_contain=('sendMessageToAll path = ',)), log))
_add('alarm-hide-launcher', _AF,
     'AlarmMainActivity: MAIN/LAUNCHER intent-filter removed (no launcher icon; activity kept)',
     lambda tree, log: _report(_alarm_hide_launcher(tree), log))
_add('alarm-relabel', _AF,
     'Application and launcher activity label -> "Phone alarms" (es: "Alarmas del teléfono")',
     lambda tree, log: _report(_alarm_relabel(tree), log))


# ---------------------------------------------------------------- family detection

def detect_family(tree):
    """Detect the family from the manifest and marker classes."""
    manifest = E.read(os.path.join(tree, 'AndroidManifest.xml'))
    pkg_m = re.search(r'package="([^"]+)"', manifest)
    pkg = pkg_m.group(1) if pkg_m else ''
    if pkg == OLD_PKG:
        phone_marker = os.path.join(tree, 'smali_classes2', 'com', 'samsung', 'android',
                                    'shealthmonitor', 'version', 'api',
                                    'AppVersionCheckResponse.smali')
        return 'shm-phone' if os.path.exists(phone_marker) else 'shm-watch'
    if pkg == ALARM_PKG:
        return 'alarm-watch'
    return 'unknown:' + pkg


class PatternRequiredError(RuntimeError):
    """A listed pattern did not match (or raised): the build must abort rather than sign an
    APK that is missing a patch. .report carries the partial results for diagnosis."""
    def __init__(self, message, report=None):
        super().__init__(message)
        self.report = report or []


def run_profile(tree, ids, dry=False):
    """Run the listed patterns IN ORDER. Returns [(id, desc, log)]. Every listed pattern is
    implicitly required: an exception or an empty result aborts with PatternRequiredError."""
    unknown = [i for i in ids if i not in PATTERNS]
    if unknown:
        raise PatternRequiredError('unknown pattern ids in profile: ' + ', '.join(unknown))
    report, fatal = [], []
    for pid in ids:
        p = PATTERNS[pid]
        log = []
        if not dry:
            try:
                p['run'](tree, log)
            except Exception as e:  # noqa: BLE001
                log.append(f'EXCEPTION: {e}')
            errs = [x for x in log if x.startswith('EXCEPTION')]
            if errs:
                fatal.append(f"pattern '{pid}' raised: {errs[0]}")
            elif not log:
                fatal.append(f"pattern '{pid}' matched nothing (did the base change? check the anchors)")
        report.append((pid, p['desc'], log))
        if fatal:
            break
    if fatal:
        raise PatternRequiredError('BUILD FAILED - ' + '; '.join(fatal), report)
    return report
