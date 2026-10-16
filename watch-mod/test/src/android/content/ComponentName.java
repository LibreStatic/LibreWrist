package android.content;

/** Host-test double of the framework ComponentName (run-tests.sh only, never dexed). */
public class ComponentName {
    private final String pkg;
    private final String cls;

    public ComponentName(String pkg, String cls) {
        this.pkg = pkg;
        this.cls = cls;
    }

    public String getClassName() {
        return cls;
    }

    public String getPackageName() {
        return pkg;
    }
}
