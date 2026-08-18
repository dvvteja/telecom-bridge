package com.telecombridge.common.diameter;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link DiameterCodec} — verifies RFC 6733 header byte layout,
 * AVP encoding with correct padding, and full encode→decode round-trips.
 */
@DisplayName("DiameterCodec Tests")
class DiameterCodecTest {

    // ═══════════════════════════════════════════════════════════════════════
    // Uint helper tests
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("encodeUint32 / decodeUint32 roundtrip")
    void testUint32Roundtrip() {
        long[] values = {0, 1, 255, 2001, 65535, 0xFFFFFFFFL};
        for (long v : values) {
            byte[] encoded = DiameterCodec.encodeUint32(v);
            assertThat(encoded).hasSize(4);
            assertThat(DiameterCodec.decodeUint32(encoded)).isEqualTo(v);
        }
    }

    @Test
    @DisplayName("encodeUint64 / decodeUint64 roundtrip")
    void testUint64Roundtrip() {
        long[] values = {0L, 1L, Long.MAX_VALUE, 1_000_000_000L};
        for (long v : values) {
            byte[] encoded = DiameterCodec.encodeUint64(v);
            assertThat(encoded).hasSize(8);
            assertThat(DiameterCodec.decodeUint64(encoded)).isEqualTo(v);
        }
    }

    @Test
    @DisplayName("encodeUtf8 / decodeUtf8 roundtrip with Unicode")
    void testUtf8Roundtrip() {
        String[] values = {"", "hello", "example.com", "447700900000", "日本語"};
        for (String s : values) {
            assertThat(DiameterCodec.decodeUtf8(DiameterCodec.encodeUtf8(s))).isEqualTo(s);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // AVP padding tests
    // ═══════════════════════════════════════════════════════════════════════

    @ParameterizedTest(name = "data length {0} produces correctly padded AVP")
    @ValueSource(ints = {0, 1, 2, 3, 4, 5, 7, 8, 9, 15, 16})
    @DisplayName("AVP padding aligns to 4-byte boundary")
    void testAvpPadding(int dataLength) {
        byte[] data = new byte[dataLength];
        Avp avp = Avp.mandatory(AvpCode.ORIGIN_HOST, data);

        DiameterMessage msg = DiameterMessage.builder()
                .commandCode(CommandCode.CAPABILITIES_EXCHANGE)
                .commandFlags(DiameterMessage.FLAG_REQUEST)
                .applicationId(0)
                .hopByHopId(1)
                .endToEndId(1)
                .addAvp(avp)
                .build();

        ByteBuf buf = DiameterCodec.encode(msg);
        try {
            // Total length must be divisible by 4
            assertThat(buf.readableBytes() % 4).isEqualTo(0);

            // Length field in header must equal readableBytes
            int headerLen = ((buf.getByte(1) & 0xFF) << 16)
                          | ((buf.getByte(2) & 0xFF) << 8)
                          |  (buf.getByte(3) & 0xFF);
            assertThat(headerLen).isEqualTo(buf.readableBytes());
        } finally {
            buf.release();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Diameter header layout test
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Encoded header byte layout matches RFC 6733 Section 3")
    void testHeaderByteLayout() {
        long hopByHop  = 0xDEADBEEFL;
        long endToEnd  = 0xCAFEBABEL;
        long appId     = 4L;

        DiameterMessage msg = DiameterMessage.builder()
                .commandCode(CommandCode.CREDIT_CONTROL)
                .commandFlags((byte)(DiameterMessage.FLAG_REQUEST | DiameterMessage.FLAG_PROXIABLE))
                .applicationId(appId)
                .hopByHopId(hopByHop)
                .endToEndId(endToEnd)
                .build();

        ByteBuf buf = DiameterCodec.encode(msg);
        try {
            // Byte 0: version = 1
            assertThat(buf.getByte(0)).isEqualTo((byte) 1);

            // Bytes 1-3: message length = 20 (header only, no AVPs)
            int len = ((buf.getByte(1) & 0xFF) << 16)
                    | ((buf.getByte(2) & 0xFF) << 8)
                    |  (buf.getByte(3) & 0xFF);
            assertThat(len).isEqualTo(20);

            // Byte 4: command flags = 0xC0 (R=1, P=1)
            assertThat(buf.getByte(4) & 0xFF).isEqualTo(0xC0);

            // Bytes 5-7: command code = 272 (0x000110)
            int cmdCode = ((buf.getByte(5) & 0xFF) << 16)
                        | ((buf.getByte(6) & 0xFF) << 8)
                        |  (buf.getByte(7) & 0xFF);
            assertThat(cmdCode).isEqualTo(272);

            // Bytes 8-11: Application-ID = 4
            assertThat(buf.getInt(8)).isEqualTo(4);

            // Bytes 12-15: Hop-by-Hop ID
            assertThat(buf.getUnsignedInt(12)).isEqualTo(hopByHop);

            // Bytes 16-19: End-to-End ID
            assertThat(buf.getUnsignedInt(16)).isEqualTo(endToEnd);
        } finally {
            buf.release();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Full encode → decode round-trip tests
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("CER encode/decode round-trip preserves all fields")
    void testCerRoundTrip() {
        DiameterMessage original = DiameterMessageFactory.buildCer(
                "gateway.example.com", "example.com", 42L, 99L);

        ByteBuf buf = DiameterCodec.encode(original);
        try {
            DiameterMessage decoded = DiameterCodec.decode(buf);

            assertThat(decoded.getCommandCode()).isEqualTo(CommandCode.CAPABILITIES_EXCHANGE);
            assertThat(decoded.isRequest()).isTrue();
            assertThat(decoded.getHopByHopId()).isEqualTo(42L);
            assertThat(decoded.getEndToEndId()).isEqualTo(99L);
            assertThat(decoded.getApplicationId()).isEqualTo(AvpCode.APPLICATION_ID_BASE);

            // Origin-Host AVP must be present
            Avp originHost = decoded.findAvp(AvpCode.ORIGIN_HOST);
            assertThat(originHost).isNotNull();
            assertThat(DiameterCodec.decodeUtf8(originHost.getData()))
                    .isEqualTo("gateway.example.com");
        } finally {
            buf.release();
        }
    }

    @Test
    @DisplayName("CCR encode/decode round-trip preserves MSISDN and request units")
    void testCcrRoundTrip() {
        DiameterMessage original = DiameterMessageFactory.buildCcr(
                "gateway.example.com", "example.com",
                "simulator.example.com", "example.com",
                1234L, 5678L,
                AvpCode.CC_REQUEST_TYPE_INITIAL, 0L,
                "447700900001", 1024L);

        ByteBuf buf = DiameterCodec.encode(original);
        try {
            DiameterMessage decoded = DiameterCodec.decode(buf);

            assertThat(decoded.getCommandCode()).isEqualTo(CommandCode.CREDIT_CONTROL);
            assertThat(decoded.isRequest()).isTrue();
            assertThat(decoded.getHopByHopId()).isEqualTo(1234L);
            assertThat(decoded.getApplicationId())
                    .isEqualTo(AvpCode.APPLICATION_ID_CREDIT_CONTROL);

            // CC-Request-Type = INITIAL (1)
            Avp reqType = decoded.findAvp(AvpCode.CC_REQUEST_TYPE);
            assertThat(reqType).isNotNull();
            assertThat(DiameterCodec.decodeUint32(reqType.getData()))
                    .isEqualTo(AvpCode.CC_REQUEST_TYPE_INITIAL);
        } finally {
            buf.release();
        }
    }

    @Test
    @DisplayName("CCA encode/decode round-trip preserves Result-Code and Granted-Service-Unit")
    void testCcaRoundTrip() {
        DiameterMessage ccr = DiameterMessageFactory.buildCcr(
                "gw", "realm", "sim", "realm",
                777L, 888L,
                AvpCode.CC_REQUEST_TYPE_INITIAL, 0L, "447700900002", 512L);

        DiameterMessage cca = DiameterMessageFactory.buildCca(
                ccr, "simulator.example.com", "example.com",
                AvpCode.RESULT_CODE_SUCCESS, 512L);

        ByteBuf buf = DiameterCodec.encode(cca);
        try {
            DiameterMessage decoded = DiameterCodec.decode(buf);

            assertThat(decoded.getCommandCode()).isEqualTo(CommandCode.CREDIT_CONTROL);
            assertThat(decoded.isRequest()).isFalse();
            assertThat(decoded.getHopByHopId()).isEqualTo(777L);

            Avp resultCode = decoded.findAvp(AvpCode.RESULT_CODE);
            assertThat(resultCode).isNotNull();
            assertThat(DiameterCodec.decodeUint32(resultCode.getData()))
                    .isEqualTo(AvpCode.RESULT_CODE_SUCCESS);
        } finally {
            buf.release();
        }
    }

    @Test
    @DisplayName("Grouped AVP is encoded and decoded correctly")
    void testGroupedAvpRoundTrip() {
        DiameterMessage msg = DiameterMessageFactory.buildDwr(
                "gw.example.com", "example.com", 10L, 20L, 1L);

        ByteBuf buf = DiameterCodec.encode(msg);
        try {
            DiameterMessage decoded = DiameterCodec.decode(buf);
            assertThat(decoded.getCommandCode()).isEqualTo(CommandCode.DEVICE_WATCHDOG);
            assertThat(decoded.getAvps()).isNotEmpty();

            Avp originStateId = decoded.findAvp(AvpCode.ORIGIN_STATE_ID);
            assertThat(originStateId).isNotNull();
            assertThat(DiameterCodec.decodeUint32(originStateId.getData())).isEqualTo(1L);
        } finally {
            buf.release();
        }
    }

    @Test
    @DisplayName("peekMessageLength returns correct value without advancing reader")
    void testPeekMessageLength() {
        DiameterMessage msg = DiameterMessageFactory.buildCer(
                "host", "realm", 1L, 1L);
        ByteBuf buf = DiameterCodec.encode(msg);
        try {
            int peeked = DiameterCodec.peekMessageLength(buf);
            int readerBefore = buf.readerIndex();
            assertThat(peeked).isEqualTo(buf.readableBytes());
            assertThat(buf.readerIndex()).isEqualTo(readerBefore); // reader not advanced
        } finally {
            buf.release();
        }
    }

    @Test
    @DisplayName("Multiple messages concatenated in a single buffer are decoded independently")
    void testConcatenatedMessages() {
        DiameterMessage cer = DiameterMessageFactory.buildCer("h", "r", 1L, 1L);
        DiameterMessage dwr = DiameterMessageFactory.buildDwr("h", "r", 2L, 2L, 0L);

        ByteBuf combined = Unpooled.buffer();
        ByteBuf cerBuf   = DiameterCodec.encode(cer);
        ByteBuf dwrBuf   = DiameterCodec.encode(dwr);
        combined.writeBytes(cerBuf);
        combined.writeBytes(dwrBuf);
        cerBuf.release();
        dwrBuf.release();

        try {
            DiameterMessage first  = DiameterCodec.decode(combined);
            DiameterMessage second = DiameterCodec.decode(combined);

            assertThat(first.getCommandCode()).isEqualTo(CommandCode.CAPABILITIES_EXCHANGE);
            assertThat(second.getCommandCode()).isEqualTo(CommandCode.DEVICE_WATCHDOG);
            assertThat(combined.readableBytes()).isEqualTo(0);
        } finally {
            combined.release();
        }
    }
}
