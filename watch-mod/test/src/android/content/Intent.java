package android.content;

/** Host-test double of the framework Intent (run-tests.sh only, never dexed). */
public class Intent {
    private final ComponentName component;

    public Intent(Context ctx, Class<?> cls) {
        this.component = cls == null ? null : new ComponentName("test", cls.getName());
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

    public ComponentName getComponent() {
        return component;
    }
}
