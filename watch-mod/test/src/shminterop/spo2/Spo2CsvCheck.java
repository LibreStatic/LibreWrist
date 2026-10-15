package shminterop.spo2;

import shminterop.hsdk.PpgSample;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Host tool: runs the adapter's estimator (same channel orientation as the adapter) over a probe
 * CSV (rows {@code A,wall_ms,ts,m,green,gs,red,rs,ir,irs}) and prints the per-second values and
 * medians, to compare with {@code analyze.py --swap}.
 *
 *   java -cp <classes> shminterop.spo2.Spo2CsvCheck <probe.csv>
 */
public final class Spo2CsvCheck {
    private Spo2CsvCheck() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.err.println("usage: Spo2CsvCheck <probe.csv>");
            System.exit(2);
        }
        final List<Integer> values = new ArrayList<Integer>();
        final List<Double> rs = new ArrayList<Double>();
        final int[] total = {0};
        Spo2Estimator est = new Spo2Estimator(new Spo2Estimator.Output() {
            @Override public void onSecond(long secondEpochMs, int value, double r) {
                total[0]++;
                System.out.printf("%d %3d  R=%s%n", secondEpochMs / 1000L, value,
                        Double.isNaN(r) ? "-" : String.format("%.3f", r));
                if (value > 0) {
                    values.add(value);
                    rs.add(r);
                }
            }
        });
        BufferedReader in = new BufferedReader(new FileReader(args[0]));
        try {
            String line;
            while ((line = in.readLine()) != null) {
                String[] f = line.split(",");
                if (f.length < 10 || !f[0].equals("A")) continue;
                SpO2Adapter.feed(est, new PpgSample(Long.parseLong(f[2]),
                        Integer.parseInt(f[4]), Integer.parseInt(f[5]),
                        Integer.parseInt(f[6]), Integer.parseInt(f[7]),
                        Integer.parseInt(f[8]), Integer.parseInt(f[9])));
            }
        } finally {
            in.close();
        }
        Collections.sort(values);
        Collections.sort(rs);
        System.out.printf("swap=%b seconds=%d valid=%d median=%s medianR=%s%n", SpO2Adapter.SWAP_RED_IR,
                total[0], values.size(),
                values.isEmpty() ? "-" : String.valueOf(values.get(values.size() / 2)),
                rs.isEmpty() ? "-" : String.format("%.3f", rs.get(rs.size() / 2)));
    }
}
