package shminterop.spo2;

import java.io.File;
import java.io.FileOutputStream;
import java.util.List;

/**
 * Plain main-based tests of the GET_DATA answer chunking and of the night store (run-tests.sh
 * only). Exits non-zero on failure.
 */
public final class Spo2AnswerTest {
    private static int failures;
    static final long T0 = 1_700_000_000_000L;

    public static void main(String[] args) throws Exception {
        denseGridWithZeros();
        chunkInvariants();
        emptyWindow();
        cap();
        flooringToSeconds();
        storeRoundTrip();
        System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    static void check(boolean ok, String what) {
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + what);
        }
    }

    static void eq(Object expected, Object actual, String what) {
        check(expected == null ? actual == null : expected.equals(actual),
                what + " expected=" + expected + " actual=" + actual);
    }

    static final Spo2Answer.Source EVEN = new Spo2Answer.Source() {
        @Override public int valueAt(long secondMs) {
            long s = (secondMs - T0) / 1000L;
            return s % 2 == 0 ? 95 : 0;
        }
    };

    static void denseGridWithZeros() {
        List<Spo2Answer.Chunk> c = Spo2Answer.build(T0, T0 + 9_000L, EVEN);
        eq(1, c.size(), "one chunk");
        Spo2Answer.Chunk k = c.get(0);
        eq(10, k.fullSize, "full size, end second included");
        eq(10, k.size, "size");
        eq(0, k.remainCount, "remain");
        for (int i = 0; i < 10; i++) {
            eq(T0 + i * 1000L, k.times[i], "time " + i);
            eq(i % 2 == 0 ? 95 : 0, k.spo2[i], "value " + i);
        }
    }

    static void chunkInvariants() {
        int total = 1850;
        List<Spo2Answer.Chunk> c = Spo2Answer.build(T0, T0 + (total - 1) * 1000L, EVEN);
        eq(4, c.size(), "chunks");
        int delivered = 0;
        long expectTime = T0;
        for (Spo2Answer.Chunk k : c) {
            check(k.size <= Spo2Answer.CHUNK && k.size > 0, "chunk size bound " + k.size);
            eq(total, k.fullSize, "full size on every chunk");
            eq(k.size, k.spo2.length, "spo2 length");
            eq(k.size, k.times.length, "time length");
            delivered += k.size;
            eq(total - delivered, k.remainCount, "remain count");
            for (long t : k.times) {
                eq(expectTime, t, "contiguous grid");
                expectTime += 1000L;
            }
        }
        eq(total, delivered, "everything delivered");
        eq(0, c.get(c.size() - 1).remainCount, "last chunk has nothing remaining");
        eq(600, c.get(0).size, "first chunk full");
        eq(50, c.get(3).size, "last chunk partial");
    }

    static void emptyWindow() {
        check(Spo2Answer.build(T0, T0, EVEN).isEmpty(), "end == start");
        check(Spo2Answer.build(T0 + 5000L, T0, EVEN).isEmpty(), "end < start");
        check(Spo2Answer.build(-5L, 1000L, EVEN).isEmpty(), "negative start");
        eq(0, Spo2Answer.entries(0L, 0L), "entries of nothing");
    }

    static void cap() {
        List<Spo2Answer.Chunk> c = Spo2Answer.build(T0, T0 + 200_000L * 1000L, EVEN);
        int sum = 0;
        for (Spo2Answer.Chunk k : c) {
            sum += k.size;
            eq(Spo2Answer.MAX_ENTRIES, k.fullSize, "capped full size");
        }
        eq(Spo2Answer.MAX_ENTRIES, sum, "capped total");
    }

    static void flooringToSeconds() {
        List<Spo2Answer.Chunk> c = Spo2Answer.build(T0 + 400L, T0 + 3_900L, EVEN);
        Spo2Answer.Chunk k = c.get(0);
        eq(4, k.fullSize, "start 0.4 s .. end 3.9 s floors to seconds 0..3");
        eq(T0, k.times[0], "first second floored");
    }

    static void storeRoundTrip() throws Exception {
        File dir = new File(System.getProperty("test.tmp", "."), "spo2-store-test");
        dir.mkdirs();
        File f = new File(dir, "night.bin");
        f.delete();
        Spo2Store s = new Spo2Store();
        s.attach(f);
        s.put(T0, 96);
        s.put(T0 + 1000L, 0);
        s.put(T0 + 2000L, 88);
        eq(88, s.get(T0 + 2000L), "get");
        eq(0, s.get(T0 + 9000L), "missing second is 0");
        eq(T0 + 2000L, s.newest(), "newest");
        check(s.flush(), "flush");
        eq(3 * Spo2Store.RECORD_BYTES, (int) f.length(), "file size");

        // A partial trailing record (crash mid-write) is ignored.
        FileOutputStream o = new FileOutputStream(f, true);
        o.write(new byte[] {1, 2, 3});
        o.close();

        Spo2Store t = new Spo2Store();
        t.attach(f);
        eq(3, t.load(), "records loaded");
        eq(96, t.get(T0), "restored value 0");
        eq(88, t.get(T0 + 2000L), "restored value 2");

        t.clear();
        check(!f.exists(), "clear removes the file");
        eq(0, t.size(), "clear empties memory");
        dir.delete();
    }
}
