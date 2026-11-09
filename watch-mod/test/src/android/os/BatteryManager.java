package android.os;

/** Host-test double of the framework BatteryManager (run-tests.sh only, never dexed). */
public class BatteryManager {
    public static final int BATTERY_PROPERTY_CAPACITY = 4;

    /** What getIntProperty returns for the capacity; a test sets it, or {@link #failure}. */
    public int capacity = 50;
    public RuntimeException failure;

    public int getIntProperty(int id) {
        if (failure != null) throw failure;
        return id == BATTERY_PROPERTY_CAPACITY ? capacity : Integer.MIN_VALUE;
    }
}
