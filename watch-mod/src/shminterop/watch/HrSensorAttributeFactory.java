package shminterop.watch;

/**
 * Builds an instance of a named class through its no-arg constructor by reflection, with no
 * direct reference to that class. Pure Java (no Android types) so the failure path can be
 * tested on the host. Never throws: any failure, LinkageError included, returns null.
 */
final class HrSensorAttributeFactory {
    private HrSensorAttributeFactory() {}

    static Object newInstance(String className) {
        try {
            return newInstance(Class.forName(className));
        } catch (Throwable t) {
            return null;
        }
    }

    static Object newInstance(Class<?> clazz) {
        try {
            return clazz.getDeclaredConstructor().newInstance();
        } catch (Throwable t) {
            return null;
        }
    }
}
