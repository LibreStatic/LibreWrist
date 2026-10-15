package shminterop.spo2;

import java.util.ArrayList;
import java.util.List;

/**
 * The GET_DATA answer of the sleep SpO2 sensor as a list of chunks (pure Java).
 *
 * A dense 1 Hz grid: one entry for every second from floor(start) to floor(end), both included,
 * value 0 where nothing is known. Chunks hold at most {@link #CHUNK} entries; every chunk
 * carries the size of the WHOLE answer (secFullSize), its own length (secSize) and the number of
 * entries still to come after it (secRemainCount), the fields the SHM consumer accumulates on.
 */
public final class Spo2Answer {
    public static final int CHUNK = 600;
    public static final int MAX_ENTRIES = 86400;

    /** Looks up the value of one second (epoch ms, multiple of 1000); 0 = unknown. */
    public interface Source {
        int valueAt(long secondMs);
    }

    public static final class Chunk {
        public final int fullSize;
        public final int size;
        public final int remainCount;
        public final int[] spo2;
        public final long[] times;

        Chunk(int fullSize, int size, int remainCount, int[] spo2, long[] times) {
            this.fullSize = fullSize;
            this.size = size;
            this.remainCount = remainCount;
            this.spo2 = spo2;
            this.times = times;
        }
    }

    private Spo2Answer() {}

    /** Number of grid entries for the window, capped; 0 when the window is empty or invalid. */
    public static int entries(long startMs, long endMs) {
        if (endMs <= startMs || startMs < 0) return 0;
        long a = Math.floorDiv(startMs, 1000L), b = Math.floorDiv(endMs, 1000L);
        long count = b - a + 1;
        return (int) Math.min(count, MAX_ENTRIES);
    }

    /** The chunks of the answer; an empty list means the EMPTY event. */
    public static List<Chunk> build(long startMs, long endMs, Source source) {
        List<Chunk> out = new ArrayList<Chunk>();
        int total = entries(startMs, endMs);
        if (total == 0) return out;
        long first = Math.floorDiv(startMs, 1000L) * 1000L;
        int done = 0;
        while (done < total) {
            int len = Math.min(CHUNK, total - done);
            int[] v = new int[len];
            long[] t = new long[len];
            for (int i = 0; i < len; i++) {
                t[i] = first + (long) (done + i) * 1000L;
                v[i] = source.valueAt(t[i]);
            }
            done += len;
            out.add(new Chunk(total, len, total - done, v, t));
        }
        return out;
    }
}
