package shminterop.spo2;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;

/**
 * Small bounded journal of the adapter's overnight events, so a morning log export can explain a
 * night even after logcat rotated. One line per event ("epochMs text") in an append-only file,
 * mirrored in memory. Pure Java, thread-safe; every I/O error is swallowed because diagnostics
 * must never break recording.
 */
public final class NightLog {
    /** Entries kept (newest). */
    public static final int KEEP = 300;
    /** The file is rewritten with the newest KEEP lines once it holds more than this. */
    public static final int HIGH_WATER = 400;

    public static final class Entry {
        public final long epochMs;
        public final String text;

        Entry(long epochMs, String text) {
            this.epochMs = epochMs;
            this.text = text;
        }
    }

    private final List<Entry> entries = new ArrayList<Entry>();
    private File file;
    private int fileLines; // lines currently in the file

    /**
     * Sets the persistence file (null = memory only), loads its lines (unparsable ones are
     * skipped) and writes out entries recorded before the attach.
     */
    public synchronized void attach(File f) {
        file = f;
        fileLines = 0;
        if (f == null) return;
        try {
            List<Entry> early = new ArrayList<Entry>(entries);
            entries.clear();
            if (f.isFile()) {
                BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
                try {
                    String line;
                    while ((line = r.readLine()) != null) {
                        fileLines++;
                        Entry e = parse(line);
                        if (e != null) entries.add(e);
                    }
                } finally {
                    r.close();
                }
            }
            if (!early.isEmpty()) {
                entries.addAll(early);
                writeLines(early, true);
                fileLines += early.size();
            }
            trim();
        } catch (Throwable ignored) {
            // keep whatever is in memory
        }
    }

    public synchronized void add(long epochMs, String text) {
        Entry e = new Entry(epochMs, sanitize(text));
        entries.add(e);
        if (file != null) {
            try {
                List<Entry> one = new ArrayList<Entry>(1);
                one.add(e);
                writeLines(one, true);
                fileLines++;
            } catch (Throwable ignored) {
                // memory copy still valid
            }
        }
        trim();
    }

    /** Entries at or after the given time, oldest first. */
    public synchronized List<Entry> since(long epochMs) {
        List<Entry> out = new ArrayList<Entry>();
        for (Entry e : entries) if (e.epochMs >= epochMs) out.add(e);
        return out;
    }

    public synchronized int size() {
        return entries.size();
    }

    /** Forgets everything, in memory and in the file. */
    public synchronized void clear() {
        entries.clear();
        fileLines = 0;
        File f = file;
        if (f != null && f.exists()) {
            try { f.delete(); } catch (Throwable ignored) {}
        }
    }

    private void trim() {
        while (entries.size() > KEEP) entries.remove(0);
        if (file != null && fileLines > HIGH_WATER) {
            try {
                writeLines(entries, false);
                fileLines = entries.size();
            } catch (Throwable ignored) {
                // retry at the next add
            }
        }
    }

    private void writeLines(List<Entry> list, boolean append) throws IOException {
        File dir = file.getParentFile();
        if (dir != null && !dir.isDirectory()) dir.mkdirs();
        Writer w = new OutputStreamWriter(new FileOutputStream(file, append), "UTF-8");
        try {
            for (Entry e : list) w.write(e.epochMs + " " + e.text + "\n");
        } finally {
            w.close();
        }
    }

    static Entry parse(String line) {
        int sp = line.indexOf(' ');
        if (sp <= 0) return null;
        try {
            return new Entry(Long.parseLong(line.substring(0, sp)), line.substring(sp + 1));
        } catch (NumberFormatException bad) {
            return null;
        }
    }

    private static String sanitize(String s) {
        if (s == null) return "";
        return s.replace('\r', ' ').replace('\n', ' ');
    }
}
