package shminterop.shmbridge.watch;

import android.content.Context;
import android.health.connect.HealthConnectException;
import android.health.connect.HealthConnectManager;
import android.health.connect.ReadRecordsRequestUsingFilters;
import android.health.connect.ReadRecordsResponse;
import android.health.connect.datatypes.BloodPressureRecord;
import android.health.connect.datatypes.BodyTemperatureRecord;
import android.health.connect.datatypes.HeartRateRecord;
import android.health.connect.datatypes.HeartRateVariabilityRmssdRecord;
import android.health.connect.datatypes.OxygenSaturationRecord;
import android.health.connect.datatypes.Record;
import android.health.connect.datatypes.RestingHeartRateRecord;
import android.health.connect.datatypes.SleepSessionRecord;
import android.health.connect.datatypes.StepsRecord;
import android.os.OutcomeReceiver;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/**
 * Health Connect reader of the watch (whatever Samsung Health has written).
 * Synchronous API via OutcomeReceiver with a direct executor (runs on the calling thread).
 */
public final class HcReader {

    private HcReader() {}

    /** Returns {"type": [...], ...} as single-line JSON. */
    public static String readAllJson(Context ctx) throws Exception {
        HealthConnectManager m = ctx.getSystemService(HealthConnectManager.class);
        if (m == null) throw new IllegalStateException("HealthConnectManager no disponible");
        JSONObject root = new JSONObject();

        readInto(root, "heart_rate", m, HeartRateRecord.class, 50);
        readInto(root, "resting_hr", m, RestingHeartRateRecord.class, 20);
        readInto(root, "hrv", m, HeartRateVariabilityRmssdRecord.class, 20);
        readInto(root, "spo2", m, OxygenSaturationRecord.class, 50);
        readInto(root, "blood_pressure", m, BloodPressureRecord.class, 50);
        readInto(root, "sleep", m, SleepSessionRecord.class, 20);
        readInto(root, "steps", m, StepsRecord.class, 20);
        readInto(root, "skin_temp", m, BodyTemperatureRecord.class, 20);
        return root.toString();
    }

    private static <T extends Record> void readInto(JSONObject root, String key,
                                                    HealthConnectManager m, Class<T> type,
                                                    int pageSize) {
        JSONArray arr = new JSONArray();
        try {
            ReadRecordsRequestUsingFilters.Builder<T> b =
                    new ReadRecordsRequestUsingFilters.Builder<>(type);
            b.setAscending(false);
            b.setPageSize(pageSize);
            final List<T>[] out = new List[1];
            final Exception[] err = new Exception[1];
            OutcomeReceiver<ReadRecordsResponse<T>, HealthConnectException> recv =
                    new OutcomeReceiver<ReadRecordsResponse<T>, HealthConnectException>() {
                        @Override public void onResult(ReadRecordsResponse<T> r) { out[0] = r.getRecords(); }
                        @Override public void onError(HealthConnectException e) { err[0] = e; }
                    };
            m.readRecords(b.build(), Runnable::run, recv);
            if (err[0] != null) throw err[0];
            List<T> records = out[0];
            if (records != null) {
                for (Record r : records) append(arr, r);
            }
            root.put(key, arr);
        } catch (Throwable t) {
            try {
                JSONObject o = new JSONObject();
                o.put("error", String.valueOf(t.getMessage()));
                o.put("partial", arr);
                root.put(key, o);
            } catch (Exception ignored) {}
        }
    }

    private static void append(JSONArray arr, Record r) {
        try {
            JSONObject o = new JSONObject();
            if (r instanceof HeartRateRecord) {
                HeartRateRecord h = (HeartRateRecord) r;
                for (HeartRateRecord.HeartRateSample s : h.getSamples()) {
                    JSONObject smp = new JSONObject();
                    smp.put("bpm", s.getBeatsPerMinute());
                    smp.put("t", s.getTime().toString());
                    arr.put(smp);
                }
                return;
            } else if (r instanceof BloodPressureRecord) {
                BloodPressureRecord b = (BloodPressureRecord) r;
                o.put("sys", b.getSystolic().getInMillimetersOfMercury());
                o.put("dia", b.getDiastolic().getInMillimetersOfMercury());
                o.put("t", b.getTime().toString());
            } else if (r instanceof OxygenSaturationRecord) {
                OxygenSaturationRecord s = (OxygenSaturationRecord) r;
                o.put("pct", s.getPercentage().getValue());
                o.put("t", s.getTime().toString());
            } else if (r instanceof RestingHeartRateRecord) {
                RestingHeartRateRecord h = (RestingHeartRateRecord) r;
                o.put("bpm", h.getBeatsPerMinute());
                o.put("t", h.getTime().toString());
            } else if (r instanceof HeartRateVariabilityRmssdRecord) {
                HeartRateVariabilityRmssdRecord h = (HeartRateVariabilityRmssdRecord) r;
                o.put("rmssd", h.getHeartRateVariabilityMillis());
                o.put("t", h.getTime().toString());
            } else if (r instanceof SleepSessionRecord) {
                SleepSessionRecord s = (SleepSessionRecord) r;
                o.put("start", s.getStartTime().toString());
                o.put("end", s.getEndTime().toString());
            } else if (r instanceof StepsRecord) {
                StepsRecord s = (StepsRecord) r;
                o.put("count", s.getCount());
                o.put("start", s.getStartTime().toString());
                o.put("end", s.getEndTime().toString());
            } else if (r instanceof BodyTemperatureRecord) {
                BodyTemperatureRecord s = (BodyTemperatureRecord) r;
                o.put("c", s.getTemperature().getInCelsius());
                o.put("t", s.getTime().toString());
            } else {
                o.put("unknown", r.getClass().getSimpleName());
            }
            arr.put(o);
        } catch (Throwable ignored) {}
    }
}
