#!/usr/bin/env python3
"""Fill the schema-2 additions of catalogue/catalogue.json (apps, platform, strings, output
behaviours, stock replacedBy, grant reasons) from the patcher app's Android resources.

One-off migration tool, kept so the result is reproducible. It only READS
patcher-app/app/src/main/res/values*/strings.xml (it deletes nothing there) and rewrites
catalogue/catalogue.json in the canonical format. Re-running it overwrites the same keys with the
same values. Run `scripts/catalogue.py check` and `sign` afterwards.

  migrate_catalogue_strings.py [--catalogue PATH] [--res DIR] [--dry-run]
"""
import argparse
import os
import re
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import catalogue as C  # noqa: E402

RES = os.path.join(ROOT, 'patcher-app', 'app', 'src', 'main', 'res')

# values-<folder> -> BCP-47 tag used in the catalogue ("" = default resources = English)
FOLDERS = {
    '': 'en', 'ar': 'ar', 'de': 'de', 'es': 'es', 'fr': 'fr', 'hi': 'hi', 'in': 'id', 'it': 'it',
    'ja': 'ja', 'ko': 'ko', 'nl': 'nl', 'pl': 'pl', 'pt': 'pt', 'ru': 'ru', 'tr': 'tr', 'uk': 'uk',
    'zh-rCN': 'zh-CN', 'zh-rTW': 'zh-TW',
}

OUTPUTS = ['shm-phone', 'shm-watch', 'bridge-watch', 'alarm-fork', 'bridge-phone', 'shealth-phone']
LABEL_RES = {o: 'output_' + o.replace('-', '_') for o in OUTPUTS}
PURPOSE_RES = {o: 'change_purpose_' + o.replace('-', '_') for o in OUTPUTS}
STOCK = {'com.samsung.android.shealthmonitor': ('stock_label_shm', 'stock_reason_shm')}
INJECT = {  # name -> (English title, description resource)
    'watch-mod': ('Health Tracking module', 'patch_watch_mod'),
    'alarm-fork': ('Alarm link module', 'patch_alarm_fork'),
}
APPS = [
    {'id': 'health-monitor', 'outputs': ['shm-phone', 'shm-watch'], 'optional': True, 'icon': 'monitor-heart'},
    {'id': 'alarm', 'outputs': ['alarm-fork'], 'optional': False, 'icon': 'alarm'},
    {'id': 'bridge', 'outputs': ['bridge-phone', 'bridge-watch'], 'optional': False, 'icon': 'swap'},
    {'id': 'samsung-health', 'outputs': ['shealth-phone'], 'optional': True, 'icon': 'favorite'},
]
# app key -> (title resource, summary resource); None = new text, see NEW_STRINGS
APP_RES = {
    'health-monitor': ('acquire_shm_title', 'acquire_shm_summary'),
    'samsung-health': ('acquire_shealth_title', 'acquire_shealth_summary'),
    'alarm': ('output_alarm_fork', None),
    'bridge': (None, None),
}

# Texts that did not exist as resources: app.alarm.summary, app.bridge.title, app.bridge.summary.
NEW_STRINGS = {
    'en': ("Keeps the watch's alarms in sync with your phone's alarms.", 'Bridge',
           "Sends your phone's notifications, calls and alarms to the watch over Bluetooth."),
    'ar': ('يبقي منبّهات الساعة متزامنة مع منبّهات هاتفك.', 'الجسر',
           'يرسل إشعارات هاتفك ومكالماته ومنبّهاته إلى الساعة عبر البلوتوث.'),
    'de': ('Hält die Wecker der Uhr mit den Weckern deines Smartphones synchron.', 'Bridge',
           'Sendet die Benachrichtigungen, Anrufe und Wecker deines Smartphones per Bluetooth an die Uhr.'),
    'es': ('Mantiene las alarmas del reloj sincronizadas con las de tu teléfono.', 'Puente',
           'Envía las notificaciones, llamadas y alarmas de tu teléfono al reloj por Bluetooth.'),
    'fr': ('Garde les alarmes de la montre synchronisées avec celles de votre téléphone.', 'Pont',
           'Envoie les notifications, les appels et les alarmes de votre téléphone à la montre par Bluetooth.'),
    'hi': ('घड़ी के अलार्म को आपके फ़ोन के अलार्म के साथ सिंक में रखता है।', 'ब्रिज',
           'आपके फ़ोन की सूचनाएँ, कॉल और अलार्म ब्लूटूथ से घड़ी तक भेजता है।'),
    'id': ('Menjaga alarm di jam tetap sinkron dengan alarm di ponsel Anda.', 'Bridge',
           'Mengirim notifikasi, panggilan, dan alarm ponsel Anda ke jam melalui Bluetooth.'),
    'it': ("Mantiene le sveglie dell'orologio sincronizzate con quelle del tuo telefono.", 'Bridge',
           "Invia all'orologio, tramite Bluetooth, le notifiche, le chiamate e le sveglie del tuo telefono."),
    'ja': ('ウォッチのアラームをスマートフォンのアラームと同期させます。', 'ブリッジ',
           'スマートフォンの通知、通話、アラームをBluetoothでウォッチに送ります。'),
    'ko': ('워치의 알람을 휴대전화의 알람과 동기화합니다.', '브리지',
           '휴대전화의 알림, 전화, 알람을 블루투스로 워치에 전달합니다.'),
    'nl': ('Houdt de wekkers van het horloge synchroon met de wekkers van je telefoon.', 'Bridge',
           'Stuurt de meldingen, oproepen en wekkers van je telefoon via Bluetooth naar het horloge.'),
    'pl': ('Utrzymuje alarmy na zegarku zsynchronizowane z alarmami na telefonie.', 'Mostek',
           'Przesyła powiadomienia, połączenia i alarmy z telefonu na zegarek przez Bluetooth.'),
    'pt': ('Mantém os alarmes do relógio sincronizados com os do seu celular.', 'Ponte',
           'Envia as notificações, chamadas e alarmes do seu celular para o relógio por Bluetooth.'),
    'ru': ('Синхронизирует будильники на часах с будильниками телефона.', 'Мост',
           'Передаёт уведомления, звонки и будильники телефона на часы по Bluetooth.'),
    'tr': ('Saatteki alarmları telefonunuzun alarmlarıyla eşit tutar.', 'Köprü',
           'Telefonunuzun bildirimlerini, aramalarını ve alarmlarını Bluetooth ile saate gönderir.'),
    'uk': ('Синхронізує будильники на годиннику з будильниками телефона.', 'Міст',
           'Передає сповіщення, дзвінки та будильники телефона на годинник через Bluetooth.'),
    'zh-CN': ('让手表上的闹钟与手机的闹钟保持同步。', '桥接应用',
              '通过蓝牙把手机的通知、来电和闹钟发送到手表。'),
    'zh-TW': ('讓手錶上的鬧鐘與手機的鬧鐘保持同步。', '橋接應用程式',
              '透過藍牙把手機的通知、來電和鬧鐘傳送到手錶。'),
}

PLATFORM = {
    'companionApp': {
        'package': 'com.samsung.android.app.watchmanager',
        'watchPlugins': [
            'com.samsung.android.waterplugin',
            'com.samsung.android.heartplugin',
            'com.samsung.wearable.watch6plugin',
            'com.samsung.wearable.watch7plugin',
            'com.samsung.wearable.watchuniteplugin',
        ],
    },
    'healthPlatform': {
        'package': 'com.samsung.android.service.health',
        'settingsAction': 'com.samsung.android.service.health.intent.action.START_SETTINGS_ACTIVITY',
        'settingsComponent': 'com.samsung.android.service.health/.wear.settings.home.SettingsMainActivity',
        'titleViewId': 'app:id/title',
        'devModeViewId': 'app:id/dev_mode_subtitle',
        'taps': 10,
        'requiredBy': ['shm-watch'],
    },
    'logPackages': {'phone': [], 'watch': ['com.samsung.android.wear.shealth']},
    'revertSettings': [{'device': 'watch', 'kind': 'secureSetting', 'key': 'doze_always_on', 'default': '1',
                        'why': 'grant.why.always_on'}],
}

START = {'action': '${PKG_PREFIX}.shmbridge.START'}
OUTPUT_EXTRAS = {
    'bridge-watch': {'start': dict(receiver='.BridgeAlarmReceiver', **START), 'companionLink': True,
                     'singleCopy': True},
    'bridge-phone': {'start': dict(receiver='.CommsBootReceiver', **START), 'knownSigner': True,
                     'statusAuthority': '${PKG_PREFIX}.shmbridge.phone.status', 'singleCopy': True},
}


def android_unescape(raw):
    """Plain text of an Android string resource value (already XML-decoded)."""
    s = raw.strip()
    if len(s) >= 2 and s[0] == '"' and s[-1] == '"':
        s = s[1:-1]
    else:
        s = re.sub(r'\s+', ' ', s)
    return re.sub(r'\\(u[0-9a-fA-F]{4}|.)', lambda m: {'n': '\n', 't': '\t'}.get(m.group(1))
                  or (chr(int(m.group(1)[1:], 16)) if m.group(1)[0] == 'u' and len(m.group(1)) == 5
                      else m.group(1)), s)


def load_resources(res_dir):
    """tag -> {resource name: text}; also returns the list of (tag, name, why) needing review."""
    out, flagged = {}, []
    for folder, tag in FOLDERS.items():
        path = os.path.join(res_dir, 'values' + ('-' + folder if folder else ''), 'strings.xml')
        table = {}
        for el in ET.parse(path).getroot().iter('string'):
            name = el.get('name')
            if len(el):
                flagged.append((tag, name, 'markup'))
            text = android_unescape(''.join(el.itertext()))
            if re.search(r'%(\d+\$)?[sdf]', text):
                flagged.append((tag, name, 'format args'))
            table[name] = text
        out[tag] = table
    return out, flagged


def grant_why_resource(output_id, g):
    """Python twin of grantReason() in ChangeExplanations.kt: the grant_why_* suffix or None."""
    watch = output_id not in ('bridge-phone', 'shm-phone', 'shealth-phone')
    kind = g['kind']
    if kind == 'notificationListener':
        return 'listener_watch' if watch else 'listener_phone'
    if kind == 'deviceIdleWhitelist':
        return {'alarm-fork': 'battery_alarm', 'bridge-phone': 'battery_bridge_phone'}.get(output_id, 'battery_bridge')
    if kind == 'globalSetting':
        return 'hidden_api'
    name = {'permission': lambda: g['name'].rsplit('.', 1)[-1], 'appop': lambda: g['op'],
            'secureSetting': lambda: g['key']}[kind]()
    simple = {'ANSWER_PHONE_CALLS': 'calls', 'READ_PHONE_STATE': 'phone_state',
              'ACCESS_RESTRICTED_SETTINGS': 'restricted', 'MANAGE_ONGOING_CALLS': 'ongoing_calls',
              'doze_always_on': 'always_on'}
    if name in simple:
        return simple[name]
    if name == 'POST_NOTIFICATIONS':
        return {'bridge-phone': 'notifications_phone', 'alarm-fork': 'notifications_alarm',
                'shm-watch': 'notifications_shm', 'shm-phone': 'notifications_shm',
                'shealth-phone': 'notifications_shealth'}.get(output_id, 'notifications_watch')
    if name == 'BLUETOOTH_CONNECT':
        return 'bluetooth_watch' if watch else 'bluetooth_phone'
    if name == 'SYSTEM_ALERT_WINDOW':
        return 'overlay_watch' if watch else 'overlay_phone'
    if name == 'SCHEDULE_EXACT_ALARM':
        return 'exact_alarm_shm' if output_id == 'shm-watch' else 'exact_alarm_bridge'
    if name == 'USE_FULL_SCREEN_INTENT':
        return 'full_screen_alarm' if output_id == 'alarm-fork' else 'full_screen_bridge'
    return None


USED = set()   # resource names that were migrated (for the format-args/markup report)


def build_strings(cat, res):
    """The `strings` table: tag -> {key: text}; en first, other locales in en key order."""
    en = res['en']
    # (key, resource name | None, English literal | None); en defines the key set
    entries = []
    for a in APPS:
        t, s = APP_RES[a['id']]
        entries.append((f'app.{a["id"]}.title', t, None))
        entries.append((f'app.{a["id"]}.summary', s, None))
    for o in OUTPUTS:
        entries.append((f'output.{o}.label', LABEL_RES[o], None))
        entries.append((f'output.{o}.purpose', PURPOSE_RES[o], None))
    for pkg, (lab, why) in STOCK.items():
        entries.append((f'stock.{pkg}.label', lab, None))
        entries.append((f'stock.{pkg}.reason', why, None))
    for name, (title, desc) in INJECT.items():
        entries.append((f'inject.{name}.title', None, title))
        entries.append((f'inject.{name}.description', desc, None))
    for pid in cat['patterns']:
        entries.append((f'pattern.{pid}.description', 'patch_' + pid.replace('-', '_'), None))
    why_res = {}
    for oid, lst in cat['grants'].items():
        for g in lst:
            w = grant_why_resource(oid, g)
            if w:
                why_res['grant.why.' + w] = 'grant_why_' + w
    # the revert-only setting is not among the grants, but its reason is a shared key too
    why_res['grant.why.always_on'] = 'grant_why_always_on'
    for key, r in why_res.items():
        entries.append((key, r, None))
    USED.update(r for _, r, _ in entries if r)

    new_idx = {'app.alarm.summary': 0, 'app.bridge.title': 1, 'app.bridge.summary': 2}
    out = {}
    for tag, table in res.items():
        d = {}
        for key, rname, literal in entries:
            if key in new_idx:
                text = NEW_STRINGS[tag][new_idx[key]]
            elif literal is not None:
                text = literal if tag == 'en' else None
            else:
                text = table.get(rname)
                if text is None and tag == 'en':
                    raise SystemExit(f'default resources lack {rname} (for {key})')
            if text is not None:
                d[key] = text
        out[tag] = d
    return out, why_res


def migrate(cat, res):
    strings, why_res = build_strings(cat, res)
    cat['schema'] = 2
    for o in cat['outputs']:
        for k in ('start', 'companionLink', 'knownSigner', 'statusAuthority', 'singleCopy'):
            o.pop(k, None)
        o.update(OUTPUT_EXTRAS.get(o['id'], {}))
    for oid, lst in cat['grants'].items():
        for g in lst:
            g.pop('why', None)
            w = grant_why_resource(oid, g)
            if w:
                g['why'] = 'grant.why.' + w
    for s in cat['stockAppsToDisable']:
        if s['package'] == 'com.samsung.android.shealthmonitor':
            s['replacedBy'] = 'shm-watch'
    cat['apps'] = APPS
    cat['platform'] = PLATFORM
    cat['strings'] = strings
    return cat


def main():
    ap = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    ap.add_argument('--catalogue', default=C.CATALOGUE)
    ap.add_argument('--res', default=RES)
    ap.add_argument('--dry-run', action='store_true')
    args = ap.parse_args()
    cat = C.load_raw(args.catalogue)
    res, flagged = load_resources(args.res)
    cat = migrate(cat, res)
    for tag, name, why in flagged:
        if name not in USED:
            continue
        print(f'review: {tag}/{name}: {why}', file=sys.stderr)
    for tag, d in cat['strings'].items():
        print(f'{tag}: {len(d)} strings')
    if not args.dry_run:
        C.write_catalogue(cat, args.catalogue)
    return 0


if __name__ == '__main__':
    sys.exit(main())
