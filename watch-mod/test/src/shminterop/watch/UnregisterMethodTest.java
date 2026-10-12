package shminterop.watch;

import java.lang.reflect.Method;

/** Host tests for UnregisterMethod: the overload choice across framework variants. */
public final class UnregisterMethodTest {
    interface Listener {}
    static final class ListenerImpl implements Listener {}

    /** The framework SHM 1.5.1.022 was built against. */
    public static final class ClassicManager {
        int calls;
        public void unregisterListener(Listener l, int type) { calls += type; }
        public void unregisterListener(Listener l) { calls += 1000; }
    }

    /** Same parameters, another return type (a plain NoSuchMethodError case). */
    public static final class BooleanManager {
        int calls;
        public boolean unregisterListener(Listener l, int type) { calls += type; return true; }
    }

    /** Only the listener-wide overload. */
    public static final class SingleManager {
        int calls;
        public void unregisterListener(Listener l) { calls += 1000; }
    }

    public static final class NoManager {
        public void unregisterListener(String s, int type) {}
    }

    private static int failures;

    private static void check(boolean ok, String what) {
        System.out.println((ok ? "PASS " : "FAIL ") + what);
        if (!ok) failures++;
    }

    public static void main(String[] args) throws Exception {
        ClassicManager classic = new ClassicManager();
        Method m = UnregisterMethod.pick(ClassicManager.class, Listener.class);
        UnregisterMethod.call(m, classic, new ListenerImpl(), 49);
        check(classic.calls == 49, "classic framework: (listener, int) is preferred");

        BooleanManager bool = new BooleanManager();
        m = UnregisterMethod.pick(BooleanManager.class, Listener.class);
        UnregisterMethod.call(m, bool, new ListenerImpl(), 49);
        check(bool.calls == 49, "boolean return type: still (listener, int)");

        SingleManager single = new SingleManager();
        m = UnregisterMethod.pick(SingleManager.class, Listener.class);
        UnregisterMethod.call(m, single, new ListenerImpl(), 49);
        check(single.calls == 1000, "only (listener): falls back to it");

        check(UnregisterMethod.pick(NoManager.class, Listener.class) == null, "no usable overload: null");

        if (failures > 0) {
            System.exit(1);
        }
    }
}
