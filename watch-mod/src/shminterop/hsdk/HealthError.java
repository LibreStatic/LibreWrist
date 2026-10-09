package shminterop.hsdk;

/** Errors reported by the Health Tracking Service on the listener (docs/health-tracking-protocol.md, 6.1). */
public enum HealthError {
    /** Wire code 0: the runtime permission for the tracker type is missing. */
    PERMISSION_ERROR,
    /** Any other wire code: the caller was refused by the service policy (see Health Platform developer mode). */
    SDK_POLICY_ERROR;

    public static HealthError fromWireCode(int code) {
        return code == 0 ? PERMISSION_ERROR : SDK_POLICY_ERROR;
    }
}
