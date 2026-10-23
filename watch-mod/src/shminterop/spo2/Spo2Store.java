package shminterop.spo2;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.TreeMap;

/**
 * Per-second SpO2 values of the current night, keyed by the epoch ms of the second, with an
 * append-only file so a process restart mid-night does not lose them. Pure Java, thread-safe.
 *
 * File format (no header): fixed 9-byte records, big-endian long secondEpochMs + one byte value
 * (0 = invalid second, else 70..100). A trailing partial record is ignored on load.
 */
public final class Spo2Store {
    public static final int RECORD_BYTES = 9;

    private final TreeMap<Long, Integer> values = new TreeMap<Long, Integer>();
    private final ByteArrayOutputStream pending = new ByteArrayOutputStream();
    private File file;

    /** Sets the persistence file (null = memory only). Does not load it. */
    public synchronized void attach(File f) {
        file = f;
    }

    public synchronized void put(long secondMs, int value) {
        values.put(secondMs, value);
        try {
            DataOutputStream d = new DataOutputStream(pending);
            d.writeLong(secondMs);
            d.writeByte(value);
        } catch (IOException impossible) {
            // ByteArrayOutputStream does not throw
        }
    }

    /** The stored value, or 0 when the second is missing. */
    public synchronized int get(long secondMs) {
        Integer v = values.get(secondMs);
        return v == null ? 0 : v;
    }

    public synchronized int size() {
        return values.size();
    }

    /** Epoch ms of the newest stored second, or Long.MIN_VALUE when empty. */
    public synchronized long newest() {
        return values.isEmpty() ? Long.MIN_VALUE : values.lastKey();
    }

    /** Number of stored seconds with a valid value (non-zero). */
    public synchronized int validCount() {
        int n = 0;
        for (Integer v : values.values()) if (v != 0) n++;
        return n;
    }

    /** Epoch ms of the oldest stored second, or Long.MAX_VALUE when empty. */
    public synchronized long oldest() {
        return values.isEmpty() ? Long.MAX_VALUE : values.firstKey();
    }

    /** Forgets everything, in memory and in the file. */
    public synchronized void clear() {
        values.clear();
        pending.reset();
        if (file != null && file.exists() && !file.delete()) {
            try { new FileOutputStream(file).close(); } catch (IOException ignored) {}
        }
    }

    /** Appends the buffered records to the file. Returns false on an I/O failure. */
    public synchronized boolean flush() {
        if (file == null || pending.size() == 0) return true;
        try {
            File dir = file.getParentFile();
            if (dir != null && !dir.isDirectory()) dir.mkdirs();
            FileOutputStream o = new FileOutputStream(file, true);
            try {
                pending.writeTo(o);
            } finally {
                o.close();
            }
            pending.reset();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** Replaces the memory content with the file's. Returns the number of records read. */
    public synchronized int load() {
        values.clear();
        pending.reset();
        if (file == null || !file.isFile()) return 0;
        int n = 0;
        try {
            DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(new FileInputStream(file)));
            try {
                while (true) {
                    long t = in.readLong();
                    int v = in.readUnsignedByte();
                    values.put(t, v);
                    n++;
                }
            } catch (EOFException end) {
                // complete records read; a partial one is dropped
            } finally {
                in.close();
            }
        } catch (IOException e) {
            // keep what was read
        }
        return n;
    }
}
