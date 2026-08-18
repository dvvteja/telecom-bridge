package com.telecombridge.common.diameter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Represents a single Diameter AVP (Attribute-Value Pair) as defined by RFC 6733, Section 4.
 *
 * <p>AVP Header format (non-vendor-specific):
 * <pre>
 *  0                   1                   2                   3
 *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                       AVP Code                                |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |V M P r r r r r|                AVP Length                     |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                        Vendor-ID (opt)                        |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                           Data ...                            |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * </pre>
 *
 * <p>Flags:
 * <ul>
 *   <li>V (Vendor-Specific) — bit 7</li>
 *   <li>M (Mandatory)       — bit 6</li>
 *   <li>P (Protected)       — bit 5</li>
 * </ul>
 */
public class Avp {

    /** Flag bit: Vendor-Specific. */
    public static final byte FLAG_VENDOR = (byte) 0x80;

    /** Flag bit: Mandatory. */
    public static final byte FLAG_MANDATORY = (byte) 0x40;

    private final int code;
    private final byte flags;
    private final long vendorId;   // 0 if non-vendor-specific
    private final byte[] data;     // raw octets (for leaf AVPs)
    private final List<Avp> grouped; // populated for Grouped AVPs, null otherwise

    // ─── Leaf AVP constructor ───────────────────────────────────────────────

    public Avp(int code, byte flags, long vendorId, byte[] data) {
        this.code     = code;
        this.flags    = flags;
        this.vendorId = vendorId;
        this.data     = data.clone();
        this.grouped  = null;
    }

    // ─── Grouped AVP constructor ────────────────────────────────────────────

    public Avp(int code, byte flags, long vendorId, List<Avp> children) {
        this.code     = code;
        this.flags    = flags;
        this.vendorId = vendorId;
        this.data     = null;
        this.grouped  = new ArrayList<>(children);
    }

    // ─── Factory helpers ────────────────────────────────────────────────────

    /** Create a Mandatory, non-vendor-specific leaf AVP. */
    public static Avp mandatory(int code, byte[] data) {
        return new Avp(code, FLAG_MANDATORY, 0L, data);
    }

    /** Create a non-mandatory, non-vendor-specific leaf AVP. */
    public static Avp optional(int code, byte[] data) {
        return new Avp(code, (byte) 0x00, 0L, data);
    }

    /** Create a Mandatory Grouped AVP. */
    public static Avp grouped(int code, List<Avp> children) {
        return new Avp(code, FLAG_MANDATORY, 0L, children);
    }

    // ─── Accessors ──────────────────────────────────────────────────────────

    public int getCode()     { return code; }
    public byte getFlags()   { return flags; }
    public long getVendorId(){ return vendorId; }

    public boolean isVendorSpecific() { return (flags & FLAG_VENDOR)     != 0; }
    public boolean isMandatory()      { return (flags & FLAG_MANDATORY)  != 0; }
    public boolean isGrouped()        { return grouped != null; }

    /**
     * Returns raw data bytes (for leaf AVPs). Returns {@code null} for Grouped AVPs.
     */
    public byte[] getData() {
        return data == null ? null : data.clone();
    }

    /**
     * Returns children (for Grouped AVPs). Returns an empty list for leaf AVPs.
     */
    public List<Avp> getGrouped() {
        return grouped == null ? Collections.emptyList() : Collections.unmodifiableList(grouped);
    }

    @Override
    public String toString() {
        if (isGrouped()) {
            return String.format("Avp{code=%d, flags=0x%02X, grouped=%s}", code, flags, grouped);
        }
        return String.format("Avp{code=%d, flags=0x%02X, dataLen=%d}",
                code, flags, data == null ? 0 : data.length);
    }
}
