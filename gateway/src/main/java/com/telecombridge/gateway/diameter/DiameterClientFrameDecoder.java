package com.telecombridge.gateway.diameter;

import com.telecombridge.common.diameter.DiameterCodec;
import com.telecombridge.common.diameter.DiameterMessage;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Client-side Diameter frame decoder. Accumulates incoming bytes until a
 * complete message is available, then passes the decoded {@link DiameterMessage}
 * to the next handler in the pipeline.
 *
 * <p>This is the client-side equivalent of the simulator's {@code DiameterFrameDecoder}.
 */
public class DiameterClientFrameDecoder extends ByteToMessageDecoder {

    private static final Logger log = LoggerFactory.getLogger(DiameterClientFrameDecoder.class);
    private static final int MIN_BYTES_FOR_LENGTH = 4;

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        while (in.readableBytes() >= MIN_BYTES_FOR_LENGTH) {
            in.markReaderIndex();

            int msgLen = DiameterCodec.peekMessageLength(in);

            if (msgLen < DiameterMessage.HEADER_LENGTH) {
                log.error("Invalid Diameter message length {} — closing channel", msgLen);
                ctx.close();
                return;
            }

            if (in.readableBytes() < msgLen) {
                in.resetReaderIndex();
                return;
            }

            try {
                DiameterMessage msg = DiameterCodec.decode(in);
                out.add(msg);
            } catch (Exception e) {
                log.error("Failed to decode Diameter response: {}", e.getMessage(), e);
                in.resetReaderIndex();
                ctx.close();
                return;
            }
        }
    }
}
