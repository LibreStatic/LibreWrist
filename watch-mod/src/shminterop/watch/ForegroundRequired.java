package shminterop.watch;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Remembers, per service class name, whether the last start of that service had to be a
 * foreground start (startForegroundService), so the service's onCreate knows whether it must
 * call startForeground. Thread-safe: starts come from receivers and workers, onCreate from the
 * main thread.
 */
final class ForegroundRequired {
    private static final Set<String> REQUIRED =
            java.util.Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    private ForegroundRequired() {}

    static void set(String serviceClass, boolean required) {
        if (serviceClass == null) return;
        if (required) REQUIRED.add(serviceClass);
        else REQUIRED.remove(serviceClass);
    }

    static boolean isRequired(String serviceClass) {
        return serviceClass != null && REQUIRED.contains(serviceClass);
    }

    /** Tests. */
    static void clear() {
        REQUIRED.clear();
    }
}
