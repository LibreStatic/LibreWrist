package android.content;

/** Host-test double of the framework Intent (run-tests.sh only, never dexed). */
public class Intent {
    public Intent(Context ctx, Class<?> cls) {
    }

    public Intent setAction(String action) {
        return this;
    }

    public Intent putExtra(String name, int value) {
        return this;
    }

    public Intent putExtra(String name, long value) {
        return this;
    }
}
