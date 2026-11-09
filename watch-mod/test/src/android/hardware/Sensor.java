package android.hardware;

/** Test double: a sensor is its type and a name. */
public class Sensor {
    public static final int TYPE_LOW_LATENCY_OFFBODY_DETECT = 34;

    private final int type;
    private final String name;

    public Sensor(int type, String name) {
        this.type = type;
        this.name = name;
    }

    public int getType() { return type; }

    @Override public String toString() { return "{Sensor name=\"" + name + "\", type=" + type + "}"; }
}
