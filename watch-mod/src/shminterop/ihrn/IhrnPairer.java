package shminterop.ihrn;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Joins the PPG_GREEN and ACCELEROMETER samples of the Health Tracking Service by identical
 * timestamp (both come from the same hub event). Green samples leave in arrival order: the head
 * waits for its accelerometer sample for at most {@link #HOLD_MS}, then goes out with the last
 * accelerometer values seen and {@code pairingFailed}. Accelerometer samples nobody claimed are
 * discarded once the green stream has passed them. Not thread-safe: one delivery thread only.
 */
final class IhrnPairer {
    /** How long an unmatched green sample is held back. */
    static final long HOLD_MS = 2000L;
    /** Bound of the unclaimed accelerometer samples (about 80 s at 25 Hz). */
    static final int MAX_ACCEL = 2000;
    /** Bound of the held green samples; the oldest goes out unpaired beyond it. */
    static final int MAX_GREEN = 2000;

    private static final class Green {
        final long tsMs;
        final int value;
        final int status;
        final long arrivalMs;

        Green(long tsMs, int value, int status, long arrivalMs) {
            this.tsMs = tsMs;
            this.value = value;
            this.status = status;
            this.arrivalMs = arrivalMs;
        }
    }

    private final ArrayDeque<Green> greens = new ArrayDeque<Green>();
    private final LinkedHashMap<Long, int[]> accels = new LinkedHashMap<Long, int[]>();
    private int[] lastAccel = new int[3];
    private long lastAccelTs = Long.MIN_VALUE;
    private long lastEmittedTs = Long.MIN_VALUE;

    /** [arrivalMs] is any monotonic clock, the same one later passed to {@link #drain}. */
    void addGreen(long tsMs, int green, int status, long arrivalMs) {
        greens.addLast(new Green(tsMs, green, status, arrivalMs));
    }

    void addAccel(long tsMs, int x, int y, int z) {
        int[] v = new int[] {x, y, z};
        accels.put(tsMs, v);
        if (tsMs >= lastAccelTs) {
            lastAccelTs = tsMs;
            lastAccel = v;
        }
    }

    /** The samples that are ready at [nowMs], oldest first. */
    List<PairedSample> drain(long nowMs) {
        List<PairedSample> out = new ArrayList<PairedSample>();
        while (!greens.isEmpty()) {
            Green g = greens.peekFirst();
            int[] a = accels.remove(g.tsMs);
            boolean overflow = greens.size() > MAX_GREEN;
            if (a != null) {
                greens.pollFirst();
                out.add(new PairedSample(g.tsMs, g.value, g.status, a[0], a[1], a[2], false));
            } else if (nowMs - g.arrivalMs >= HOLD_MS || overflow) {
                greens.pollFirst();
                out.add(new PairedSample(g.tsMs, g.value, g.status,
                        lastAccel[0], lastAccel[1], lastAccel[2], true));
            } else {
                break;
            }
            lastEmittedTs = Math.max(lastEmittedTs, g.tsMs);
        }
        // Accelerometer samples the green stream has already passed can never be claimed.
        Iterator<Map.Entry<Long, int[]>> it = accels.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, int[]> e = it.next();
            if (e.getKey() <= lastEmittedTs || accels.size() > MAX_ACCEL) it.remove();
            else break; // insertion order is arrival order, not timestamp order: stop at the first keeper
        }
        return out;
    }

    /** Held green samples (tests). */
    int pending() {
        return greens.size();
    }
}
