package com.telecombridge.common.diameter;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Diameter message codec implementing RFC 6733 framing and AVP padding rules.
 *
 * <h3>Diameter Header (20 bytes)</h3>
 * <pre>
 *   Offset  Len  Field
 *   0       1    Version (= 1)
 *   1       3    Message Length (total, including header)
 *   4       1    Command Flags  (R P E T rrrr)
 *   5       3    Command Code
 *   8       4    Application-ID
 *  12       4    Hop-by-Hop Identifier
 *  16       4    End-to-End Identifier
 * </pre>
 *
 * <h3>AVP Header (8 bytes base, +4 if vendor-specific)</h3>
 * <pre>
 *   Offset  Len  Field
 *   0       4    AVP Code
 *   4       1    Flags (V M P rrrrr)
 *   5       3    AVP Length (header + data, NOT including padding)
 *   8       4    Vendor-ID (present only when V flag set)
 *   ...          Data
 *   ...          Padding (0–3 bytes, zero-filled, to align to 4-byte boundary)
 * </pre>
 */
public final class DiameterCodec {

    private static final Logger log = LoggerFactory.getLogger(DiameterCodec.class);

    private static final int AVP_HEADER_BASE    = 8;   // without Vendor-ID
    private static final int AVP_HEADER_VENDOR  = 12;  // with Vendor-ID

    private DiameterCodec() {}

    // ═══════════════════════════════════════════════════════════════════════
    // ENCODE
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Encodes a {@link DiameterMessage} to a Netty {@link ByteBuf}.
     * The caller is responsible for releasing the buffer when done.
     */
    public static ByteBuf encode(DiameterMessage msg) {
        ByteBuf avpBuf = Unpooled.buffer(256);
        try {
            for (Avp avp : msg.getAvps()) {
                encodeAvp(avp, avpBuf);
            }

            int totalLength = DiameterMessage.HEADER_LENGTH + avpBuf.readableBytes();
            ByteBuf out = Unpooled.buffer(totalLength);

            // Header
            out.writeByte(DiameterMessage.VERSION);            // Version
            writeUint24(out, totalLength);                     // Message Length
            out.writeByte(msg.getCommandFlags());              // Command Flags
            writeUint24(out, msg.getCommandCode());            // Command Code
            out.writeInt((int) msg.getApplicationId());        // Application-ID (4 bytes)
            out.writeInt((int) msg.getHopByHopId());           // Hop-by-Hop ID
            out.writeInt((int) msg.getEndToEndId());           // End-to-End ID

            // AVPs
            out.writeBytes(avpBuf);
            return out;
        } finally {
            avpBuf.release();
        }
    }

    private static void encodeAvp(Avp avp, ByteBuf buf) {
        if (avp.isGrouped()) {
            // Encode children first to calculate length
            ByteBuf childBuf = Unpooled.buffer(128);
            try {
                for (Avp child : avp.getGrouped()) {
                    encodeAvp(child, childBuf);
                }
                int headerLen = avp.isVendorSpecific() ? AVP_HEADER_VENDOR : AVP_HEADER_BASE;
                int avpLen    = headerLen + childBuf.readableBytes();

                buf.writeInt(avp.getCode());             // AVP Code
                buf.writeByte(avp.getFlags());           // Flags
                writeUint24(buf, avpLen);                // AVP Length
                if (avp.isVendorSpecific()) {
                    buf.writeInt((int) avp.getVendorId());
                }
                buf.writeBytes(childBuf);
                writePadding(buf, avpLen);
            } finally {
                childBuf.release();
            }
        } else {
            byte[] data = avp.getData();
            if (data == null) data = new byte[0];

            int headerLen = avp.isVendorSpecific() ? AVP_HEADER_VENDOR : AVP_HEADER_BASE;
            int avpLen    = headerLen + data.length;

            buf.writeInt(avp.getCode());             // AVP Code
            buf.writeByte(avp.getFlags());           // Flags
            writeUint24(buf, avpLen);                // AVP Length
            if (avp.isVendorSpecific()) {
                buf.writeInt((int) avp.getVendorId());
            }
            buf.writeBytes(data);                    // Data
            writePadding(buf, avpLen);               // Pad to 4-byte boundary
        }
    }

    /** Writes 0–3 zero bytes so that total written bytes are a multiple of 4. */
    private static void writePadding(ByteBuf buf, int avpLength) {
        int pad = (4 - (avpLength % 4)) % 4;
        for (int i = 0; i < pad; i++) {
            buf.writeByte(0);
        }
    }

    /** Writes an unsigned 24-bit (3-byte) big-endian integer. */
    private static void writeUint24(ByteBuf buf, int value) {
        buf.writeByte((value >> 16) & 0xFF);
        buf.writeByte((value >> 8)  & 0xFF);
        buf.writeByte(value         & 0xFF);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // DECODE
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Decodes a Diameter message from the given {@link ByteBuf}.
     * The buffer's reader index must be positioned at the start of the message header.
     * After this call the reader index is advanced past the entire message.
     *
     * @throws IllegalArgumentException if the buffer is too short or the version is wrong
     */
    public static DiameterMessage decode(ByteBuf buf) {
        if (buf.readableBytes() < DiameterMessage.HEADER_LENGTH) {
            throw new IllegalArgumentException("Buffer too short for Diameter header: "
                    + buf.readableBytes());
        }

        int startIndex = buf.readerIndex();

        byte version = buf.readByte();
        if (version != DiameterMessage.VERSION) {
            throw new IllegalArgumentException("Unsupported Diameter version: " + version);
        }

        int  msgLen       = readUint24(buf);
        byte cmdFlags     = buf.readByte();
        int  cmdCode      = readUint24(buf);
        long appId        = buf.readUnsignedInt();
        long hopByHopId   = buf.readUnsignedInt();
        long endToEndId   = buf.readUnsignedInt();

        int avpBytesTotal = msgLen - DiameterMessage.HEADER_LENGTH;

        if (buf.readableBytes() < avpBytesTotal) {
            throw new IllegalArgumentException(
                    "Buffer too short for AVPs: need " + avpBytesTotal
                    + " have " + buf.readableBytes());
        }

        // Slice out only the AVP region
        ByteBuf avpSlice = buf.slice(buf.readerIndex(), avpBytesTotal);
        List<Avp> avps   = decodeAvps(avpSlice, avpBytesTotal);

        buf.readerIndex(startIndex + msgLen); // advance past whole message

        log.trace("Decoded: cmd={} flags=0x{} appId={} hbh={} avps={}",
                cmdCode, String.format("%02X", cmdFlags), appId, hopByHopId, avps.size());

        return DiameterMessage.builder()
                .commandFlags(cmdFlags)
                .commandCode(cmdCode)
                .applicationId(appId)
                .hopByHopId(hopByHopId)
                .endToEndId(endToEndId)
                .addAvps(avps)
                .build();
    }

    /**
     * Returns the total message length encoded in the 3-byte length field
     * without consuming the buffer (peek-only). Buffer must have at least 4 readable bytes.
     */
    public static int peekMessageLength(ByteBuf buf) {
        // bytes 1-3 (0-indexed) hold the 24-bit message length
        return ((buf.getByte(buf.readerIndex() + 1) & 0xFF) << 16)
             | ((buf.getByte(buf.readerIndex() + 2) & 0xFF) << 8)
             |  (buf.getByte(buf.readerIndex() + 3) & 0xFF);
    }

    // ─── Private decode helpers ─────────────────────────────────────────────

    private static List<Avp> decodeAvps(ByteBuf buf, int totalBytes) {
        List<Avp> avps  = new ArrayList<>();
        int       read  = 0;

        while (read < totalBytes && buf.readableBytes() > 0) {
            if (buf.readableBytes() < AVP_HEADER_BASE) break;

            int startReader = buf.readerIndex();
            int avpCode     = buf.readInt();
            byte avpFlags   = buf.readByte();
            int avpLength   = readUint24(buf); // includes header

            boolean vendorSpecific = (avpFlags & Avp.FLAG_VENDOR) != 0;
            int headerLen = vendorSpecific ? AVP_HEADER_VENDOR : AVP_HEADER_BASE;

            long vendorId = 0;
            if (vendorSpecific) {
                vendorId = buf.readUnsignedInt();
            }

            int dataLen   = avpLength - headerLen;
            int paddedLen = avpLength + ((4 - (avpLength % 4)) % 4);

            byte[] data = new byte[Math.max(0, dataLen)];
            if (dataLen > 0) {
                buf.readBytes(data);
            }

            // Skip padding
            int padding = paddedLen - avpLength;
            if (padding > 0 && buf.readableBytes() >= padding) {
                buf.skipBytes(padding);
            }

            avps.add(new Avp(avpCode, avpFlags, vendorId, data));
            read += paddedLen;
        }
        return avps;
    }

    /** Reads an unsigned 24-bit (3-byte) big-endian integer. */
    private static int readUint24(ByteBuf buf) {
        return ((buf.readByte() & 0xFF) << 16)
             | ((buf.readByte() & 0xFF) << 8)
             |  (buf.readByte() & 0xFF);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // AVP DATA ENCODING UTILITIES
    // ═══════════════════════════════════════════════════════════════════════

    /** Encodes a 32-bit unsigned integer into 4 bytes (big-endian). */
    public static byte[] encodeUint32(long value) {
        return new byte[]{
            (byte) ((value >> 24) & 0xFF),
            (byte) ((value >> 16) & 0xFF),
            (byte) ((value >> 8)  & 0xFF),
            (byte) (value         & 0xFF)
        };
    }

    /** Decodes a 4-byte big-endian value to an unsigned long. */
    public static long decodeUint32(byte[] data) {
        return ((data[0] & 0xFFL) << 24)
             | ((data[1] & 0xFFL) << 16)
             | ((data[2] & 0xFFL) << 8)
             |  (data[3] & 0xFFL);
    }

    /** Encodes a 64-bit unsigned integer into 8 bytes (big-endian). */
    public static byte[] encodeUint64(long value) {
        return new byte[]{
            (byte) ((value >> 56) & 0xFF), (byte) ((value >> 48) & 0xFF),
            (byte) ((value >> 40) & 0xFF), (byte) ((value >> 32) & 0xFF),
            (byte) ((value >> 24) & 0xFF), (byte) ((value >> 16) & 0xFF),
            (byte) ((value >> 8)  & 0xFF), (byte) (value         & 0xFF)
        };
    }

    /** Decodes an 8-byte big-endian value to a signed long. */
    public static long decodeUint64(byte[] data) {
        long result = 0;
        for (int i = 0; i < 8; i++) {
            result = (result << 8) | (data[i] & 0xFFL);
        }
        return result;
    }

    /** Encodes a UTF-8 string. */
    public static byte[] encodeUtf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /** Decodes a UTF-8 string. */
    public static String decodeUtf8(byte[] data) {
        return new String(data, StandardCharsets.UTF_8);
    }
}
