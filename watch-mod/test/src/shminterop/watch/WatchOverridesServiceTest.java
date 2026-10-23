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
        runningServiceGoesForegroundOnLaterFallback();
        ensureDoesNothingForPlainStart();
        apneaTitleOnlyWhenShown();
        apneaTitleFromVendorPackageAndKeepsExisting();
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

    static void runningServiceGoesForegroundOnLaterFallback() {
        ForegroundRequired.clear();
        FakeContext ctx = new FakeContext();
        SvcA svc = new SvcA();
        WatchOverrides.startService(ctx, new Intent(ctx, SvcA.class));
        WatchOverrides.startForegroundIfRequired(svc, 7, new Notification());
        WatchOverrides.ensureForegroundIfRequired(svc);
        eq(0, svc.foregroundCalls, "plain start: no startForeground in onCreate or onStartCommand");
        // The service keeps running; the next start is refused and falls back, onCreate does not
        // run again, only onStartCommand.
        ctx.denyBackground = true;
        WatchOverrides.startService(ctx, new Intent(ctx, SvcA.class));
        WatchOverrides.ensureForegroundIfRequired(svc);
        eq(1, svc.foregroundCalls, "onStartCommand calls startForeground after the fallback");
        eq(7, svc.lastForegroundId, "with the id onCreate used");
    }

    static void ensureDoesNothingForPlainStart() {
        ForegroundRequired.clear();
        SvcB svc = new SvcB();
        WatchOverrides.ensureForegroundIfRequired(svc);
        eq(0, svc.foregroundCalls, "no remembered notification, no flag: nothing");
    }

    static void apneaTitleOnlyWhenShown() {
        ForegroundRequired.clear();
        android.content.res.Resources.STRINGS.clear();
        android.content.res.Resources.tablePackage = "shminterop.test";
        android.content.res.Resources.STRINGS.put("shminterop.test:" + WatchOverrides.APNEA_TITLE_RES, "Sleep apnea");
        FakeContext ctx = new FakeContext();
        SvcA quiet = new SvcA();
        Notification plain = new Notification();
        WatchOverrides.startService(ctx, new Intent(ctx, SvcA.class));
        WatchOverrides.startApneaForegroundIfRequired(quiet, 1, plain);
        eq(0, quiet.foregroundCalls, "apnea plain start: no notification");
        eq(null, plain.extras.getCharSequence(Notification.EXTRA_TITLE), "no title needed when not shown");

        ctx.denyBackground = true;
        SvcA shown = new SvcA();
        Notification n = new Notification();
        WatchOverrides.startService(ctx, new Intent(ctx, SvcA.class));
        WatchOverrides.startApneaForegroundIfRequired(shown, 1, n);
        eq(1, shown.foregroundCalls, "apnea fallback: notification shown");
        eq("Sleep apnea", String.valueOf(n.extras.getCharSequence(Notification.EXTRA_TITLE)), "title set");
    }

    static void apneaTitleFromVendorPackageAndKeepsExisting() {
        ForegroundRequired.clear();
        android.content.res.Resources.STRINGS.clear();
        android.content.res.Resources.tablePackage = "com.samsung.android.shealthmonitor";
        android.content.res.Resources.STRINGS.put(
                "com.samsung.android.shealthmonitor:" + WatchOverrides.APNEA_TITLE_RES, "Schlafapnoe");
        FakeContext ctx = new FakeContext();
        ctx.denyBackground = true;
        WatchOverrides.startService(ctx, new Intent(ctx, SvcA.class));
        Notification n = new Notification();
        WatchOverrides.startApneaForegroundIfRequired(new SvcA(), 1, n);
        eq("Schlafapnoe", String.valueOf(n.extras.getCharSequence(Notification.EXTRA_TITLE)),
                "falls back to the vendor resource package");

        Notification titled = new Notification();
        titled.extras.putCharSequence(Notification.EXTRA_TITLE, "Own title");
        WatchOverrides.startService(ctx, new Intent(ctx, SvcA.class));
        WatchOverrides.startApneaForegroundIfRequired(new SvcA(), 1, titled);
        eq("Own title", String.valueOf(titled.extras.getCharSequence(Notification.EXTRA_TITLE)),
                "an existing title is kept");

        android.content.res.Resources.STRINGS.clear();
        Notification missing = new Notification();
        WatchOverrides.startService(ctx, new Intent(ctx, SvcA.class));
        SvcA svc = new SvcA();
        WatchOverrides.startApneaForegroundIfRequired(svc, 1, missing);
        eq(null, missing.extras.getCharSequence(Notification.EXTRA_TITLE), "no string: left untitled");
        eq(1, svc.foregroundCalls, "still shown without a title");
    }
}
