package shminterop.watch;

import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;

/**
 * Plain main-based tests of WatchOverrides.startService / startForegroundIfRequired (run-tests.sh
 * only): a plain start leaves the service without a notification, a refused background start
 * falls back to startForegroundService and makes the service call startForeground, the flag is
 * per service class, and a later successful start clears it. Exits non-zero on failure.
 */
public final class WatchOverridesServiceTest {
    private static int failures;

    static final class SvcA extends Service {}

    static final class SvcB extends Service {}

    static final class FakeContext extends Context {
        volatile boolean denyBackground;
        int plain;
        int foreground;

        @Override public android.content.ComponentName startService(Intent intent) {
            plain++;
            if (denyBackground) throw new IllegalStateException("not allowed to start service");
            return super.startService(intent);
        }

        @Override public android.content.ComponentName startForegroundService(Intent intent) {
            foreground++;
            return super.startForegroundService(intent);
        }
    }

    public static void main(String[] args) {
        plainStartNeedsNoForeground();
        deniedStartFallsBackAndRemembers();
        flagIsPerClassAndClearedByLaterSuccess();
        System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    static void eq(Object expected, Object actual, String what) {
        if (!(expected == null ? actual == null : expected.equals(actual))) {
            failures++;
            System.out.println("FAIL: " + what + " expected=" + expected + " actual=" + actual);
        }
    }

    static void plainStartNeedsNoForeground() {
        ForegroundRequired.clear();
        FakeContext ctx = new FakeContext();
        SvcA svc = new SvcA();
        eq(SvcA.class.getName(), WatchOverrides.startService(ctx, new Intent(ctx, SvcA.class)).getClassName(),
                "component returned");
        eq(1, ctx.plain, "plain start used");
        eq(0, ctx.foreground, "no foreground start");
        WatchOverrides.startForegroundIfRequired(svc, 1, new Notification());
        eq(0, svc.foregroundCalls, "no startForeground, so no notification");
    }

    static void deniedStartFallsBackAndRemembers() {
        ForegroundRequired.clear();
        FakeContext ctx = new FakeContext();
        ctx.denyBackground = true;
        SvcA svc = new SvcA();
        eq(SvcA.class.getName(), WatchOverrides.startService(ctx, new Intent(ctx, SvcA.class)).getClassName(),
                "component returned after fallback");
        eq(1, ctx.plain, "plain start tried first");
        eq(1, ctx.foreground, "foreground start as fallback");
        WatchOverrides.startForegroundIfRequired(svc, 1, new Notification());
        eq(1, svc.foregroundCalls, "startForeground called when started as foreground");
    }

    static void flagIsPerClassAndClearedByLaterSuccess() {
        ForegroundRequired.clear();
        FakeContext ctx = new FakeContext();
        ctx.denyBackground = true;
        WatchOverrides.startService(ctx, new Intent(ctx, SvcA.class));
        SvcB other = new SvcB();
        WatchOverrides.startForegroundIfRequired(other, 1, new Notification());
        eq(0, other.foregroundCalls, "other service class unaffected");
        ctx.denyBackground = false;
        WatchOverrides.startService(ctx, new Intent(ctx, SvcA.class));
        SvcA a = new SvcA();
        WatchOverrides.startForegroundIfRequired(a, 1, new Notification());
        eq(0, a.foregroundCalls, "a later successful start clears the flag");
    }
}
