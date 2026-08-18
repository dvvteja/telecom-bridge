package com.telecombridge.common.diameter;

import java.util.ArrayList;
import java.util.List;

/**
 * Factory for building standard Diameter messages (CER, CEA, DWR, DWA, CCR, CCA).
 * Centralises AVP assembly so that both the gateway client and simulator share
 * the same well-known structures.
 */
public final class DiameterMessageFactory {

    private DiameterMessageFactory() {}

    // ═══════════════════════════════════════════════════════════════════════
    // CER – Capabilities-Exchange-Request  (RFC 6733 §5.3.1)
    // ═══════════════════════════════════════════════════════════════════════

    public static DiameterMessage buildCer(String originHost, String originRealm,
                                           long hopByHopId, long endToEndId) {
        return DiameterMessage.builder()
                .commandCode(CommandCode.CAPABILITIES_EXCHANGE)
                .commandFlags(DiameterMessage.FLAG_REQUEST)
                .applicationId(AvpCode.APPLICATION_ID_BASE)
                .hopByHopId(hopByHopId)
                .endToEndId(endToEndId)
                .addAvp(Avp.mandatory(AvpCode.ORIGIN_HOST,  DiameterCodec.encodeUtf8(originHost)))
                .addAvp(Avp.mandatory(AvpCode.ORIGIN_REALM, DiameterCodec.encodeUtf8(originRealm)))
                .addAvp(Avp.mandatory(AvpCode.VENDOR_ID,    DiameterCodec.encodeUint32(0)))
                .addAvp(Avp.mandatory(AvpCode.AUTH_APPLICATION_ID,
                        DiameterCodec.encodeUint32(AvpCode.APPLICATION_ID_CREDIT_CONTROL)))
                .build();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // CEA – Capabilities-Exchange-Answer  (RFC 6733 §5.3.2)
    // ═══════════════════════════════════════════════════════════════════════

    public static DiameterMessage buildCea(DiameterMessage cer,
                                           String originHost, String originRealm) {
        return DiameterMessage.builder()
                .commandCode(CommandCode.CAPABILITIES_EXCHANGE)
                .commandFlags((byte) 0)   // answer: R-bit cleared
                .applicationId(AvpCode.APPLICATION_ID_BASE)
                .hopByHopId(cer.getHopByHopId())
                .endToEndId(cer.getEndToEndId())
                .addAvp(Avp.mandatory(AvpCode.RESULT_CODE,
                        DiameterCodec.encodeUint32(AvpCode.RESULT_CODE_SUCCESS)))
                .addAvp(Avp.mandatory(AvpCode.ORIGIN_HOST,  DiameterCodec.encodeUtf8(originHost)))
                .addAvp(Avp.mandatory(AvpCode.ORIGIN_REALM, DiameterCodec.encodeUtf8(originRealm)))
                .addAvp(Avp.mandatory(AvpCode.VENDOR_ID,    DiameterCodec.encodeUint32(0)))
                .addAvp(Avp.mandatory(AvpCode.AUTH_APPLICATION_ID,
                        DiameterCodec.encodeUint32(AvpCode.APPLICATION_ID_CREDIT_CONTROL)))
                .addAvp(Avp.optional(AvpCode.PRODUCT_NAME, DiameterCodec.encodeUtf8("TelecomBridge-Simulator")))
                .build();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // DWR – Device-Watchdog-Request  (RFC 6733 §5.5.1)
    // ═══════════════════════════════════════════════════════════════════════

    public static DiameterMessage buildDwr(String originHost, String originRealm,
                                           long hopByHopId, long endToEndId,
                                           long originStateId) {
        return DiameterMessage.builder()
                .commandCode(CommandCode.DEVICE_WATCHDOG)
                .commandFlags(DiameterMessage.FLAG_REQUEST)
                .applicationId(AvpCode.APPLICATION_ID_BASE)
                .hopByHopId(hopByHopId)
                .endToEndId(endToEndId)
                .addAvp(Avp.mandatory(AvpCode.ORIGIN_HOST,     DiameterCodec.encodeUtf8(originHost)))
                .addAvp(Avp.mandatory(AvpCode.ORIGIN_REALM,    DiameterCodec.encodeUtf8(originRealm)))
                .addAvp(Avp.mandatory(AvpCode.ORIGIN_STATE_ID, DiameterCodec.encodeUint32(originStateId)))
                .build();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // DWA – Device-Watchdog-Answer  (RFC 6733 §5.5.2)
    // ═══════════════════════════════════════════════════════════════════════

    public static DiameterMessage buildDwa(DiameterMessage dwr,
                                           String originHost, String originRealm,
                                           long originStateId) {
        return DiameterMessage.builder()
                .commandCode(CommandCode.DEVICE_WATCHDOG)
                .commandFlags((byte) 0)
                .applicationId(AvpCode.APPLICATION_ID_BASE)
                .hopByHopId(dwr.getHopByHopId())
                .endToEndId(dwr.getEndToEndId())
                .addAvp(Avp.mandatory(AvpCode.RESULT_CODE,
                        DiameterCodec.encodeUint32(AvpCode.RESULT_CODE_SUCCESS)))
                .addAvp(Avp.mandatory(AvpCode.ORIGIN_HOST,     DiameterCodec.encodeUtf8(originHost)))
                .addAvp(Avp.mandatory(AvpCode.ORIGIN_REALM,    DiameterCodec.encodeUtf8(originRealm)))
                .addAvp(Avp.mandatory(AvpCode.ORIGIN_STATE_ID, DiameterCodec.encodeUint32(originStateId)))
                .build();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // CCR – Credit-Control-Request  (RFC 4006 §6.4.2)
    // ═══════════════════════════════════════════════════════════════════════

    public static DiameterMessage buildCcr(String originHost, String originRealm,
                                           String destinationHost, String destinationRealm,
                                           long hopByHopId, long endToEndId,
                                           int requestType, long requestNumber,
                                           String msisdn, long requestedUnits) {

        // Subscription-Id grouped AVP
        List<Avp> subIdChildren = new ArrayList<>();
        subIdChildren.add(Avp.mandatory(AvpCode.SUBSCRIPTION_ID_TYPE,
                DiameterCodec.encodeUint32(AvpCode.SUBSCRIPTION_ID_TYPE_E164)));
        subIdChildren.add(Avp.mandatory(AvpCode.SUBSCRIPTION_ID_DATA,
                DiameterCodec.encodeUtf8(msisdn)));
        Avp subscriptionId = Avp.grouped(AvpCode.SUBSCRIPTION_ID, subIdChildren);

        // Requested-Service-Unit grouped AVP
        List<Avp> rsuChildren = new ArrayList<>();
        rsuChildren.add(Avp.mandatory(AvpCode.CC_TOTAL_OCTETS,
                DiameterCodec.encodeUint64(requestedUnits)));
        Avp requestedServiceUnit = Avp.grouped(AvpCode.REQUESTED_SERVICE_UNIT, rsuChildren);

        return DiameterMessage.builder()
                .commandCode(CommandCode.CREDIT_CONTROL)
                .commandFlags((byte)(DiameterMessage.FLAG_REQUEST | DiameterMessage.FLAG_PROXIABLE))
                .applicationId(AvpCode.APPLICATION_ID_CREDIT_CONTROL)
                .hopByHopId(hopByHopId)
                .endToEndId(endToEndId)
                .addAvp(Avp.mandatory(AvpCode.ORIGIN_HOST,       DiameterCodec.encodeUtf8(originHost)))
                .addAvp(Avp.mandatory(AvpCode.ORIGIN_REALM,      DiameterCodec.encodeUtf8(originRealm)))
                .addAvp(Avp.mandatory(AvpCode.DESTINATION_HOST,  DiameterCodec.encodeUtf8(destinationHost)))
                .addAvp(Avp.mandatory(AvpCode.DESTINATION_REALM, DiameterCodec.encodeUtf8(destinationRealm)))
                .addAvp(Avp.mandatory(AvpCode.AUTH_APPLICATION_ID,
                        DiameterCodec.encodeUint32(AvpCode.APPLICATION_ID_CREDIT_CONTROL)))
                .addAvp(Avp.mandatory(AvpCode.CC_REQUEST_TYPE,
                        DiameterCodec.encodeUint32(requestType)))
                .addAvp(Avp.mandatory(AvpCode.CC_REQUEST_NUMBER,
                        DiameterCodec.encodeUint32(requestNumber)))
                .addAvp(subscriptionId)
                .addAvp(requestedServiceUnit)
                .build();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // CCA – Credit-Control-Answer  (RFC 4006 §6.4.3)
    // ═══════════════════════════════════════════════════════════════════════

    public static DiameterMessage buildCca(DiameterMessage ccr,
                                           String originHost, String originRealm,
                                           int resultCode, long grantedUnits) {
        // Extract CC-Request-Type and CC-Request-Number from the CCR
        Avp ccrTypeAvp   = ccr.findAvp(AvpCode.CC_REQUEST_TYPE);
        Avp ccrNumberAvp = ccr.findAvp(AvpCode.CC_REQUEST_NUMBER);

        long reqType   = ccrTypeAvp   != null ? DiameterCodec.decodeUint32(ccrTypeAvp.getData())   : 1L;
        long reqNumber = ccrNumberAvp != null ? DiameterCodec.decodeUint32(ccrNumberAvp.getData()) : 0L;

        // Granted-Service-Unit grouped AVP
        List<Avp> gsuChildren = new ArrayList<>();
        gsuChildren.add(Avp.mandatory(AvpCode.CC_TOTAL_OCTETS,
                DiameterCodec.encodeUint64(grantedUnits)));
        Avp grantedServiceUnit = Avp.grouped(AvpCode.GRANTED_SERVICE_UNIT, gsuChildren);

        return DiameterMessage.builder()
                .commandCode(CommandCode.CREDIT_CONTROL)
                .commandFlags(DiameterMessage.FLAG_PROXIABLE) // answer: R-bit cleared, P kept
                .applicationId(AvpCode.APPLICATION_ID_CREDIT_CONTROL)
                .hopByHopId(ccr.getHopByHopId())
                .endToEndId(ccr.getEndToEndId())
                .addAvp(Avp.mandatory(AvpCode.RESULT_CODE,
                        DiameterCodec.encodeUint32(resultCode)))
                .addAvp(Avp.mandatory(AvpCode.ORIGIN_HOST,      DiameterCodec.encodeUtf8(originHost)))
                .addAvp(Avp.mandatory(AvpCode.ORIGIN_REALM,     DiameterCodec.encodeUtf8(originRealm)))
                .addAvp(Avp.mandatory(AvpCode.AUTH_APPLICATION_ID,
                        DiameterCodec.encodeUint32(AvpCode.APPLICATION_ID_CREDIT_CONTROL)))
                .addAvp(Avp.mandatory(AvpCode.CC_REQUEST_TYPE,
                        DiameterCodec.encodeUint32(reqType)))
                .addAvp(Avp.mandatory(AvpCode.CC_REQUEST_NUMBER,
                        DiameterCodec.encodeUint32(reqNumber)))
                .addAvp(grantedServiceUnit)
                .build();
    }
}
