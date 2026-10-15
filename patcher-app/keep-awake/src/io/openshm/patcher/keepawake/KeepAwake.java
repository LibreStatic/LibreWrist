package io.openshm.patcher.keepawake;

import android.os.SystemClock;
import java.io.InputStream;
import java.lang.reflect.Method;

/**
 * Keeps a watch's CPU and screen awake while the patcher installs over Wireless debugging. Started
 * by the phone through adb as the shell user:
 *
 * <pre>
 * export CLASSPATH=/data/local/tmp/openshm-keep-awake.dex
 * exec app_process / --nice-name=openshm-keep-awake io.openshm.patcher.keepawake.KeepAwake MAX_MS HEARTBEAT_TIMEOUT_MS
 * </pre>
 *
 * The wakelock belongs to this process and changes no setting, so nothing outlives it: when the
 * process ends for any reason, the power manager drops the lock with its binder token. The process
 * ends on the first of: stdin EOF, no heartbeat byte on stdin for HEARTBEAT_TIMEOUT_MS, MAX_MS
 * since start (both clamped below), SIGHUP (adbd sends it when the adb stream dies), or a reboot.
 *
 * Output lines: {@code KEEP_AWAKE_ACQUIRED} once the lock is held, {@code KEEP_AWAKE_RELEASED
 * <reason>} at the end, or {@code KEEP_AWAKE_ERROR <message>} with exit code 1.
 */
public final class KeepAwake {
    /** Hard ceiling on one run, whatever the caller asks for. */
    private static final long MAX_CEILING_MS = 30L * 60 * 1000;
    private static final long MAX_FLOOR_MS = 1000;
    private static final long HEARTBEAT_FLOOR_MS = 5000;
    private static final long HEARTBEAT_CEILING_MS = 120_000;
    /**
     * Short on purpose: nothing can veto a goToSleep (wrist down, palm cover), so the screen is
     * turned back on as soon as one is seen. isInteractive is a cheap binder call.
     */
    private static final long POLL_MS = 500;
    /** Least time between two re-acquires, so a wake that does not take cannot spin. */
    private static final long REWAKE_MS = 250;

    // PowerManager.FULL_WAKE_LOCK | ACQUIRE_CAUSES_WAKEUP | ON_AFTER_RELEASE.
    private static final int FLAGS = 0x0000001a | 0x10000000 | 0x20000000;
    private static final String TAG = "openshm:keep-awake";
    private static final String SHELL_PACKAGE = "com.android.shell";
    /** Display.INVALID_DISPLAY: the lock is not tied to one display (the framework default). */
    private static final int ANY_DISPLAY = -1;

    private static volatile long lastBeat;
    private static volatile boolean stdinClosed;

    private KeepAwake() {
    }

    public static void main(String[] args) {
        Object power;
        long maxMs;
        long heartbeatTimeoutMs;
        try {
            if (args.length != 2) throw new IllegalArgumentException("usage: KeepAwake MAX_MS HEARTBEAT_TIMEOUT_MS");
            maxMs = clamp(Long.parseLong(args[0]), MAX_FLOOR_MS, MAX_CEILING_MS);
            heartbeatTimeoutMs = clamp(Long.parseLong(args[1]), HEARTBEAT_FLOOR_MS, HEARTBEAT_CEILING_MS);
            power = powerService();
        } catch (Throwable t) {
            fail(t);
            return;
        }

        Object token = null;
        String reason = "error";
        try {
            token = acquire(power);
            // elapsedRealtime counts deep sleep too: a suspend the lock failed to prevent still
            // counts against the limits instead of stretching them.
            long start = SystemClock.elapsedRealtime();
            lastBeat = start;
            startHeartbeatReader(System.in);
            System.out.println("KEEP_AWAKE_ACQUIRED");
            System.out.flush();

            long lastRewake = start;
            while (true) {
                Thread.sleep(POLL_MS);
                long now = SystemClock.elapsedRealtime();
                if (stdinClosed) {
                    reason = "stdin-closed";
                    break;
                }
                if (now - lastBeat >= heartbeatTimeoutMs) {
                    reason = "no-heartbeat";
                    break;
                }
                if (now - start >= maxMs) {
                    reason = "max-time";
                    break;
                }
                if (now - lastRewake >= REWAKE_MS && !isInteractive(power)) {
                    // Something put the screen to sleep anyway (wrist down, palm cover): a fresh
                    // acquire with ACQUIRE_CAUSES_WAKEUP turns it back on.
                    lastRewake = now;
                    Object fresh = acquire(power);
                    release(power, token);
                    token = fresh;
                }
            }
        } catch (Throwable t) {
            if (token != null) release(power, token);
            fail(t);
            return;
        }
        release(power, token);
        System.out.println("KEEP_AWAKE_RELEASED " + reason);
        System.out.flush();
        System.exit(0);
    }

    private static long clamp(long value, long low, long high) {
        return Math.max(low, Math.min(high, value));
    }

    private static void fail(Throwable t) {
        String message = t.getClass().getSimpleName() + (t.getMessage() != null ? ": " + t.getMessage() : "");
        System.out.println("KEEP_AWAKE_ERROR " + message.replace('\n', ' '));
        System.out.flush();
        System.exit(1);
    }

    /** Every byte on stdin counts as a heartbeat; EOF or a read error ends the run. */
    private static void startHeartbeatReader(final InputStream in) {
        Thread reader = new Thread(new Runnable() {
            @Override
            public void run() {
                byte[] buf = new byte[64];
                try {
                    while (true) {
                        int n = in.read(buf);
                        if (n < 0) break;
                        if (n > 0) lastBeat = SystemClock.elapsedRealtime();
                    }
                } catch (Throwable ignored) {
                    // Treated like EOF.
                }
                stdinClosed = true;
            }
        }, "heartbeat");
        reader.setDaemon(true);
        reader.start();
    }

    /** IPowerManager through ServiceManager (hidden APIs, hence reflection). */
    private static Object powerService() throws Exception {
        Class<?> serviceManager = Class.forName("android.os.ServiceManager");
        Object binder = serviceManager.getMethod("getService", String.class).invoke(null, "power");
        if (binder == null) throw new IllegalStateException("power service not found");
        Class<?> stub = Class.forName("android.os.IPowerManager$Stub");
        return stub.getMethod("asInterface", Class.forName("android.os.IBinder")).invoke(null, binder);
    }

    private static Method method(Object power, String name) throws NoSuchMethodException {
        for (Method m : power.getClass().getMethods()) {
            if (m.getName().equals(name)) return m;
        }
        throw new NoSuchMethodException(name);
    }

    /**
     * Calls IPowerManager.acquireWakeLock with a new token. Its parameters changed across releases
     * (lock, flags, tag, packageName, workSource, historyTag, then displayId, then a callback), so
     * the arguments are filled by type: the binder, the flags as the first int, the tag and the
     * shell package as the first two strings, the display as any later int, null for the rest.
     */
    private static Object acquire(Object power) throws Exception {
        Method acquire = method(power, "acquireWakeLock");
        Class<?> iBinder = Class.forName("android.os.IBinder");
        Object token = Class.forName("android.os.Binder").getConstructor().newInstance();
        Class<?>[] types = acquire.getParameterTypes();
        Object[] values = new Object[types.length];
        int ints = 0;
        int strings = 0;
        for (int i = 0; i < types.length; i++) {
            Class<?> type = types[i];
            if (iBinder.equals(type)) {
                values[i] = token;
            } else if (type == int.class) {
                values[i] = ints++ == 0 ? FLAGS : ANY_DISPLAY;
            } else if (type == long.class) {
                values[i] = 0L;
            } else if (type == boolean.class) {
                values[i] = false;
            } else if (type == String.class) {
                int index = strings++;
                values[i] = index == 0 ? TAG : index == 1 ? SHELL_PACKAGE : null;
            } else {
                values[i] = null;
            }
        }
        acquire.invoke(power, values);
        return token;
    }

    /** IPowerManager.releaseWakeLock(lock, flags). Best effort: the process exit releases it too. */
    private static void release(Object power, Object token) {
        try {
            Method release = method(power, "releaseWakeLock");
            Class<?>[] types = release.getParameterTypes();
            Object[] values = new Object[types.length];
            for (int i = 0; i < types.length; i++) {
                if (types[i] == int.class) values[i] = 0;
                else if (types[i] == long.class) values[i] = 0L;
                else if (types[i] == boolean.class) values[i] = false;
                else if (types[i].isInstance(token)) values[i] = token;
            }
            release.invoke(power, values);
        } catch (Throwable ignored) {
            // The lock dies with the process anyway.
        }
    }

    /** Looked up once: the poll calls it twice a second. */
    private static Method isInteractive;

    private static boolean isInteractive(Object power) {
        try {
            if (isInteractive == null) isInteractive = power.getClass().getMethod("isInteractive");
            return (Boolean) isInteractive.invoke(power);
        } catch (Throwable t) {
            // Unknown: assume awake rather than re-acquiring in a loop.
            return true;
        }
    }
}
