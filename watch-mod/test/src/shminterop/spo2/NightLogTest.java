package shminterop.spo2;

import java.io.File;
import java.io.FileOutputStream;
import java.util.List;

/** Plain main-based test of NightLog (run-tests.sh only). Exits non-zero on failure. */
public final class NightLogTest {
    private static int failures;

    static void check(boolean ok, String what) {
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + what);
        }
    }

    static File tmp(String name) {
        File d = new File(System.getProperty("test.tmp", "."), "nightlog-test");
        File f = new File(new File(d, name + "-" + System.nanoTime()), "sub/events.log");
        return f;
    }

    public static void main(String[] args) throws Exception {
        appendAndSince();
        persistence();
        bound();
        corrupt();
        bufferedBeforeAttach();
        sanitized();
        System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    static void appendAndSince() {
        NightLog n = new NightLog();
        n.add(1000, "a");
        n.add(2000, "b");
        n.add(3000, "c");
        List<NightLog.Entry> s = n.since(2000);
        check(s.size() == 2 && s.get(0).text.equals("b") && s.get(1).text.equals("c"), "since filters");
        n.clear();
        check(n.size() == 0, "clear empties");
    }

    static void persistence() {
        File f = tmp("persist"); // directory does not exist yet
        NightLog a = new NightLog();
        a.attach(f);
        a.add(10, "one");
        a.add(20, "two words");
        check(f.isFile(), "missing directory created");
        NightLog b = new NightLog();
        b.attach(f);
        List<NightLog.Entry> s = b.since(0);
        check(s.size() == 2 && s.get(1).text.equals("two words") && s.get(1).epochMs == 20, "persisted across instances");
    }

    static void bound() {
        File f = tmp("bound");
        NightLog a = new NightLog();
        a.attach(f);
        for (int i = 0; i < 1000; i++) a.add(i, "e" + i);
        check(a.size() == NightLog.KEEP, "memory bounded: " + a.size());
        NightLog b = new NightLog();
        b.attach(f);
        List<NightLog.Entry> s = b.since(0);
        check(s.size() <= NightLog.HIGH_WATER, "file bounded: " + s.size());
        check(s.get(s.size() - 1).text.equals("e999"), "newest kept");
        check(b.size() == NightLog.KEEP && b.since(0).get(0).text.equals("e" + (1000 - NightLog.KEEP)),
                "oldest kept entry is the 300th newest");
    }

    static void corrupt() throws Exception {
        File f = tmp("corrupt");
        f.getParentFile().mkdirs();
        FileOutputStream o = new FileOutputStream(f);
        o.write("garbage\n12 ok\nxx bad\n\n99 fine too\n7".getBytes("UTF-8"));
        o.close();
        NightLog n = new NightLog();
        n.attach(f);
        List<NightLog.Entry> s = n.since(0);
        check(s.size() == 2 && s.get(0).text.equals("ok") && s.get(1).text.equals("fine too"),
                "corrupt lines skipped: " + s.size());
        n.attach(new File(f.getParentFile(), "a/b/missing.log"));
        n.add(1, "x");
        check(n.size() >= 1, "usable after reattach");
    }

    static void bufferedBeforeAttach() {
        File f = tmp("buffered");
        NightLog a = new NightLog();
        a.add(1, "early1");
        a.add(2, "early2");
        a.attach(f);
        a.add(3, "late");
        NightLog b = new NightLog();
        b.attach(f);
        List<NightLog.Entry> s = b.since(0);
        check(s.size() == 3 && s.get(0).text.equals("early1") && s.get(2).text.equals("late"),
                "early entries flushed on attach: " + s.size());
    }

    static void sanitized() {
        NightLog a = new NightLog();
        a.add(1, "a\nb\r\nc");
        check(a.since(0).get(0).text.indexOf('\n') < 0 && a.since(0).get(0).text.indexOf('\r') < 0, "newlines removed");
    }
}
