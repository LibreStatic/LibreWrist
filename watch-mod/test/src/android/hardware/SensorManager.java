package android.hardware;

import java.util.HashMap;
import java.util.Map;

/** Test double: getDefaultSensor answers from a map and records the last lookup. */
public class SensorManager {
    public final Map<Integer, Sensor> sensors = new HashMap<Integer, Sensor>();
    public String lastCall;

    public Sensor getDefaultSensor(int type) {
        lastCall = "getDefaultSensor(" + type + ")";
        return sensors.get(type);
    }

    public Sensor getDefaultSensor(int type, boolean wakeUp) {
        lastCall = "getDefaultSensor(" + type + ", " + wakeUp + ")";
        return sensors.get(type);
    }
}
