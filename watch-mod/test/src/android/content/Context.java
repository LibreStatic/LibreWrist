package android.content;

import java.io.File;

/**
 * Host-test double of the framework Context (run-tests.sh only, never dexed): only the type and
 * the few members the adapters touch. On the host appContext() finds no ActivityThread and
 * returns null, which is the service-missing path.
 */
public class Context {
    public File getFilesDir() {
        return new File(".");
    }

    public ClassLoader getClassLoader() {
        return Context.class.getClassLoader();
    }

    public void startForegroundService(Intent intent) {
    }
}
