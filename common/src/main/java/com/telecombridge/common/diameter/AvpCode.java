package com.telecombridge.common.diameter;

/**
 * Diameter AVP Code constants (RFC 6733 base + RFC 4006 Ro/Gy interface).
 */
public final class AvpCode {

    private AvpCode() {}

    // ──────────────────────────────────────────────────────
    // RFC 6733 Base Protocol AVPs
    // ──────────────────────────────────────────────────────

    /** AVP 264 – Origin-Host (DiameterIdentity) */
    public static final int ORIGIN_HOST = 264;

    /** AVP 296 – Origin-Realm (DiameterIdentity) */
    public static final int ORIGIN_REALM = 296;

    /** AVP 293 – Destination-Host (DiameterIdentity) */
    public static final int DESTINATION_HOST = 293;

    /** AVP 283 – Destination-Realm (DiameterIdentity) */
    public static final int DESTINATION_REALM = 283;

    /** AVP 258 – Auth-Application-Id (Unsigned32) */
    public static final int AUTH_APPLICATION_ID = 258;

    /** AVP 268 – Result-Code (Unsigned32) */
    public static final int RESULT_CODE = 268;

    /** AVP 278 – Origin-State-Id (Unsigned32) */
    public static final int ORIGIN_STATE_ID = 278;

    /** AVP 266 – Vendor-Id (Unsigned32) */
    public static final int VENDOR_ID = 266;

    /** AVP 269 – Product-Name (UTF8String) */
    public static final int PRODUCT_NAME = 269;

    /** AVP 281 – Error-Message (UTF8String) */
    public static final int ERROR_MESSAGE = 281;

    /** AVP 480 – Accounting-Record-Type (Enumerated) */
    public static final int ACCOUNTING_RECORD_TYPE = 480;

    // ──────────────────────────────────────────────────────
    // RFC 4006 Credit-Control (Ro/Gy Interface) AVPs
    // ──────────────────────────────────────────────────────

    /** AVP 416 – CC-Request-Type (Enumerated): INITIAL=1, UPDATE=2, TERMINATE=3, EVENT=4 */
    public static final int CC_REQUEST_TYPE = 416;

    /** AVP 415 – CC-Request-Number (Unsigned32) */
    public static final int CC_REQUEST_NUMBER = 415;

    /** AVP 443 – Subscription-Id (Grouped) */
    public static final int SUBSCRIPTION_ID = 443;

    /** AVP 450 – Subscription-Id-Type (Enumerated): END_USER_E164=0, END_USER_IMSI=1 */
    public static final int SUBSCRIPTION_ID_TYPE = 450;

    /** AVP 444 – Subscription-Id-Data (UTF8String) */
    public static final int SUBSCRIPTION_ID_DATA = 444;

    /** AVP 431 – Requested-Service-Unit (Grouped) */
    public static final int REQUESTED_SERVICE_UNIT = 431;

    /** AVP 432 – Granted-Service-Unit (Grouped) */
    public static final int GRANTED_SERVICE_UNIT = 432;

    /** AVP 446 – Used-Service-Unit (Grouped) */
    public static final int USED_SERVICE_UNIT = 446;

    /** AVP 417 – CC-Time (Unsigned32) — seconds */
    public static final int CC_TIME = 417;

    /** AVP 420 – CC-Total-Octets (Unsigned64) */
    public static final int CC_TOTAL_OCTETS = 420;

    /** AVP 421 – CC-Input-Octets (Unsigned64) */
    public static final int CC_INPUT_OCTETS = 421;

    /** AVP 422 – CC-Output-Octets (Unsigned64) */
    public static final int CC_OUTPUT_OCTETS = 422;

    /** AVP 439 – Rating-Group (Unsigned32) */
    public static final int RATING_GROUP = 439;

    /** AVP 456 – Multiple-Services-Credit-Control (Grouped) */
    public static final int MULTIPLE_SERVICES_CREDIT_CONTROL = 456;

    /** AVP 459 – Service-Identifier (Unsigned32) */
    public static final int SERVICE_IDENTIFIER = 459;

    // ──────────────────────────────────────────────────────
    // CC-Request-Type enumerated values
    // ──────────────────────────────────────────────────────

    public static final int CC_REQUEST_TYPE_INITIAL   = 1;
    public static final int CC_REQUEST_TYPE_UPDATE    = 2;
    public static final int CC_REQUEST_TYPE_TERMINATE = 3;
    public static final int CC_REQUEST_TYPE_EVENT     = 4;

    // ──────────────────────────────────────────────────────
    // Subscription-Id-Type enumerated values
    // ──────────────────────────────────────────────────────

    public static final int SUBSCRIPTION_ID_TYPE_E164 = 0;
    public static final int SUBSCRIPTION_ID_TYPE_IMSI = 1;

    // ──────────────────────────────────────────────────────
    // Result-Code well-known values
    // ──────────────────────────────────────────────────────

    public static final int RESULT_CODE_SUCCESS                   = 2001;
    public static final int RESULT_CODE_CREDIT_LIMIT_REACHED      = 4012;
    public static final int RESULT_CODE_RATING_FAILED             = 5031;
    public static final int RESULT_CODE_USER_UNKNOWN              = 5030;
    public static final int RESULT_CODE_UNABLE_TO_DELIVER         = 3002;

    // ──────────────────────────────────────────────────────
    // Application IDs
    // ──────────────────────────────────────────────────────

    /** Credit Control Application (Ro/Gy). */
    public static final int APPLICATION_ID_CREDIT_CONTROL = 4;

    /** Diameter base protocol application. */
    public static final int APPLICATION_ID_BASE = 0;
}
