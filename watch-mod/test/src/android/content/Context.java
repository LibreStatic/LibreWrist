package android.content;

/**
 * Host-test double of the framework Context (run-tests.sh only, never dexed): BpAdapter only
 * needs the type. On the host appContext() finds no ActivityThread and returns null, which is
 * exactly the service-missing path the keepalive test exercises.
 */
public class Context {
}
