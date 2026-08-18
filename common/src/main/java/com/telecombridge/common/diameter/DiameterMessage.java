package com.telecombridge.common.diameter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Represents a complete Diameter message as defined by RFC 6733, Section 3.
 *
 * <p>Header format:
 * <pre>
 *  0                   1                   2                   3
 *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * | Version = 1   |              Message Length                    |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * | R P E T r r r r|            Command Code                      |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                         Application-ID                        |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                     Hop-by-Hop Identifier                     |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                     End-to-End Identifier                     |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                            AVPs ...                           |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * </pre>
 *
 * <p>Command flags:
 * <ul>
 *   <li>R (Request)  — bit 7 of flags byte</li>
 *   <li>P (Proxiable)— bit 6</li>
 *   <li>E (Error)    — bit 5</li>
 *   <li>T (Retransmit) — bit 4</li>
 * </ul>
 */
public class DiameterMessage {

    /** Diameter protocol version — always 1. */
    public static final byte VERSION = 1;

    /** Header size in bytes (fixed, 20 bytes). */
    public static final int HEADER_LENGTH = 20;

    // Command flag bits
    public static final byte FLAG_REQUEST    = (byte) 0x80;
    public static final byte FLAG_PROXIABLE  = (byte) 0x40;
    public static final byte FLAG_ERROR      = (byte) 0x20;
    public static final byte FLAG_RETRANSMIT = (byte) 0x10;

    private final byte    commandFlags;
    private final int     commandCode;
    private final long    applicationId;
    private final long    hopByHopId;
    private final long    endToEndId;
    private final List<Avp> avps;

    private DiameterMessage(Builder b) {
        this.commandFlags  = b.commandFlags;
        this.commandCode   = b.commandCode;
        this.applicationId = b.applicationId;
        this.hopByHopId    = b.hopByHopId;
        this.endToEndId    = b.endToEndId;
        this.avps          = Collections.unmodifiableList(new ArrayList<>(b.avps));
    }

    // ─── Accessors ──────────────────────────────────────────────────────────

    public byte   getCommandFlags()  { return commandFlags; }
    public int    getCommandCode()   { return commandCode; }
    public long   getApplicationId() { return applicationId; }
    public long   getHopByHopId()    { return hopByHopId; }
    public long   getEndToEndId()    { return endToEndId; }
    public List<Avp> getAvps()       { return avps; }

    public boolean isRequest() { return (commandFlags & FLAG_REQUEST) != 0; }
    public boolean isError()   { return (commandFlags & FLAG_ERROR)   != 0; }

    /**
     * Finds the first AVP with the given code (not searching into grouped AVPs).
     */
    public Avp findAvp(int avpCode) {
        for (Avp avp : avps) {
            if (avp.getCode() == avpCode) return avp;
        }
        return null;
    }

    /**
     * Creates an answer skeleton mirroring the HbH/E2E IDs and command code of a request.
     */
    public static DiameterMessage createAnswer(DiameterMessage request, long applicationId) {
        return builder()
                .commandCode(request.getCommandCode())
                .commandFlags((byte) (request.getCommandFlags() & ~FLAG_REQUEST)) // clear R bit
                .applicationId(applicationId)
                .hopByHopId(request.getHopByHopId())
                .endToEndId(request.getEndToEndId())
                .build();
    }

    @Override
    public String toString() {
        return String.format("DiameterMessage{cmd=%d, flags=0x%02X, appId=%d, hbh=%d, e2e=%d, avps=%d}",
                commandCode, commandFlags, applicationId, hopByHopId, endToEndId, avps.size());
    }

    // ─── Builder ────────────────────────────────────────────────────────────

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private byte    commandFlags  = 0;
        private int     commandCode   = 0;
        private long    applicationId = 0;
        private long    hopByHopId    = 0;
        private long    endToEndId    = 0;
        private final List<Avp> avps  = new ArrayList<>();

        public Builder commandFlags(byte flags)    { this.commandFlags  = flags;  return this; }
        public Builder commandCode(int code)       { this.commandCode   = code;   return this; }
        public Builder applicationId(long appId)   { this.applicationId = appId;  return this; }
        public Builder hopByHopId(long hbh)        { this.hopByHopId    = hbh;    return this; }
        public Builder endToEndId(long e2e)        { this.endToEndId    = e2e;    return this; }
        public Builder addAvp(Avp avp)             { this.avps.add(avp);          return this; }
        public Builder addAvps(List<Avp> list)     { this.avps.addAll(list);      return this; }

        /** Mark this message as a Request (sets R flag). */
        public Builder request() {
            this.commandFlags |= FLAG_REQUEST;
            return this;
        }

        /** Mark this message as Proxiable (sets P flag). */
        public Builder proxiable() {
            this.commandFlags |= FLAG_PROXIABLE;
            return this;
        }

        public DiameterMessage build() {
            return new DiameterMessage(this);
        }
    }
}
