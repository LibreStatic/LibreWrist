package shminterop.watch;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Picks the sensor manager's unregisterListener overload at runtime. The framework method differs
 * between watch models (the Galaxy Watch8 framework has no unregisterListener(listener, int)V,
 * which SHM 1.5.1.022 links against), so the patched call sites go through reflection instead.
 * Pure Java (no Android types) so the choice can be tested on the host.
 */
final class UnregisterMethod {
    private UnregisterMethod() {}

    /**
     * The best public instance method named unregisterListener on [managerClass] for a listener of
     * [listenerType]: (listener, int) with any return type first, then (listener). Null if neither
     * exists.
     */
    static Method pick(Class<?> managerClass, Class<?> listenerType) {
        Method single = null;
        for (Method m : managerClass.getMethods()) {
            if (!m.getName().equals("unregisterListener") || Modifier.isStatic(m.getModifiers())) {
                continue;
            }
            Class<?>[] p = m.getParameterTypes();
            if (p.length == 0 || !p[0].isAssignableFrom(listenerType)) {
                continue;
            }
            if (p.length == 2 && p[1] == int.class) {
                return m;
            }
            if (p.length == 1 && single == null) {
                single = m;
            }
        }
        return single;
    }

    /** Calls [m] (from [pick]) with the listener and, when the overload takes it, the sensor type. */
    static void call(Method m, Object manager, Object listener, int type) throws Exception {
        if (m.getParameterTypes().length == 2) {
            m.invoke(manager, listener, type);
        } else {
            m.invoke(manager, listener);
        }
    }
}
