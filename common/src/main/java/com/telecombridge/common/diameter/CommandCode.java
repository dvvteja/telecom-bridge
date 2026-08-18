package com.telecombridge.common.diameter;

/**
 * Diameter Command Code constants (RFC 6733 / RFC 4006).
 */
public final class CommandCode {

    private CommandCode() {}

    /** Capabilities-Exchange-Request / Capabilities-Exchange-Answer (CER/CEA). */
    public static final int CAPABILITIES_EXCHANGE = 257;

    /** Device-Watchdog-Request / Device-Watchdog-Answer (DWR/DWA). */
    public static final int DEVICE_WATCHDOG = 280;

    /** Disconnect-Peer-Request / Disconnect-Peer-Answer (DPR/DPA). */
    public static final int DISCONNECT_PEER = 282;

    /** Credit-Control-Request / Credit-Control-Answer (CCR/CCA) — RFC 4006. */
    public static final int CREDIT_CONTROL = 272;
}
