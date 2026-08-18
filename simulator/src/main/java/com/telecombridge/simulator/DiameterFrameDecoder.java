package com.telecombridge.simulator;

import com.telecombridge.common.diameter.DiameterCodec;
import com.telecombridge.common.diameter.DiameterMessage;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Netty {@link ByteToMessageDecoder} that accumulates incoming bytes until a
 * complete Diameter message is available, then decodes it.
 *
 * <p>Diameter framing: the message length is encoded in bytes 1–3 (24-bit big-endian)
 * of the header. We wait until the buffer has at least that many bytes, then decode.
 *
 * <p>This handler is installed as a pipeline stage before {@link DiameterServerHandler}
 * and ensures that the handler always receives fully assembled {@link DiameterMessage}
 * objects, regardless of TCP segmentation.
 */
public class DiameterFrameDecoder extends ByteToMessageDecoder {

    private static final Logger log = LoggerFactory.getLogger(DiameterFrameDecoder.class);

    /** Minimum bytes needed to read the message length field. */
    private static final int MIN_BYTES_FOR_LENGTH = 4;

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        while (in.readableBytes() >= MIN_BYTES_FOR_LENGTH) {
            in.markReaderIndex();

            // Peek at the message length (bytes 1-3) without consuming
            int msgLen = DiameterCodec.peekMessageLength(in);

            if (msgLen < DiameterMessage.HEADER_LENGTH) {
                log.error("Invalid Diameter message length: {} — closing connection", msgLen);
                ctx.close();
                return;
            }

            if (in.readableBytes() < msgLen) {
                // Not enough bytes yet — wait for more
                in.resetReaderIndex();
                return;
            }

            // Full message available — decode it
            try {
                DiameterMessage msg = DiameterCodec.decode(in);
                out.add(msg);
            } catch (Exception e) {
                log.error("Failed to decode Diameter message: {}", e.getMessage(), e);
                in.resetReaderIndex();
                ctx.close();
                return;
            }
        }
    }
}
