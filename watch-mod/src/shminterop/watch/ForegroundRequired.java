package shminterop.watch;

import android.app.Notification;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Remembers, per service class name, whether the last start of that service had to be a
 * foreground start (startForegroundService), so the service's onCreate knows whether it must
 * call startForeground, and the notification its onCreate built, so a later foreground start of
 * the running service can call startForeground too. Thread-safe: starts come from receivers and
 * workers, onCreate from the main thread.
 */
final class ForegroundRequired {
    private static final Set<String> REQUIRED =
            java.util.Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private static final Map<String, Notice> NOTICES = new ConcurrentHashMap<String, Notice>();

    /** The id and notification a service's onCreate passed to startForeground. */
    static final class Notice {
        final int id;
        final Notification notification;

        Notice(int id, Notification notification) {
            this.id = id;
            this.notification = notification;
        }
    }

    private ForegroundRequired() {}

    static void set(String serviceClass, boolean required) {
        if (serviceClass == null) return;
        if (required) REQUIRED.add(serviceClass);
        else REQUIRED.remove(serviceClass);
    }

    static boolean isRequired(String serviceClass) {
        return serviceClass != null && REQUIRED.contains(serviceClass);
    }

    static void remember(String serviceClass, int id, Notification notification) {
        if (serviceClass == null || notification == null) return;
        NOTICES.put(serviceClass, new Notice(id, notification));
    }

    static Notice remembered(String serviceClass) {
        return serviceClass == null ? null : NOTICES.get(serviceClass);
    }

    /** Tests. */
    static void clear() {
        REQUIRED.clear();
        NOTICES.clear();
    }
}
