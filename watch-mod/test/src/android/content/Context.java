package android.content;

import java.io.File;

/**
 * Host-test double of the framework Context (run-tests.sh only, never dexed): only the type and
 * the few members the adapters touch. On the host appContext() finds no ActivityThread and
 * returns null, which is the service-missing path.
 */
public class Context {
    public static final String POWER_SERVICE = "power";

    public Object getSystemService(String name) {
        return null;
    }

    public File getFilesDir() {
        return new File(".");
    }

    public ClassLoader getClassLoader() {
        return Context.class.getClassLoader();
    }

    public String getPackageName() {
        return "shminterop.test";
    }

    public android.content.pm.ApplicationInfo getApplicationInfo() {
        android.content.pm.ApplicationInfo info = new android.content.pm.ApplicationInfo();
        info.labelRes = 0x7f120001;
        return info;
    }

    public android.content.res.Resources getResources() {
        return new android.content.res.Resources();
    }

    public ComponentName startService(Intent intent) {
        return intent.getComponent();
    }

    public ComponentName startForegroundService(Intent intent) {
        return intent.getComponent();
    }
}
