package flashcache.protocol;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * WebSocket RFC 6455 implementation.
 * Handles upgrade handshake, frame encoding/decoding, and masking.
 */
public class WebSocketHandler {

    // Magic GUID defined in RFC 6455
    private static final String WS_MAGIC = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    // -----------------------------------------------------------------------
    // Opcodes
    // -----------------------------------------------------------------------

    public enum Opcode {
        CONTINUATION(0x0),
        TEXT(0x1),
        BINARY(0x2),
        CLOSE(0x8),
        PING(0x9),
        PONG(0xA);

        public final int value;

        Opcode(int value) { this.value = value; }

        public static Opcode fromValue(int v) {
            for (Opcode op : values()) {
                if (op.value == v) return op;
            }
            throw new WebSocketException("Unknown opcode: " + v);
        }
    }

    // -----------------------------------------------------------------------
    // Frame record
    // -----------------------------------------------------------------------

    public record WebSocketFrame(
            boolean fin,
            Opcode opcode,
            boolean masked,
            byte[] payload
    ) {}

    // -----------------------------------------------------------------------
    // Exception
    // -----------------------------------------------------------------------

    public static class WebSocketException extends RuntimeException {
        public WebSocketException(String message) { super(message); }
        public WebSocketException(String message, Throwable cause) { super(message, cause); }
    }

    // -----------------------------------------------------------------------
    // Handshake
    // -----------------------------------------------------------------------

    /**
     * Compute Sec-WebSocket-Accept from the client's Sec-WebSocket-Key.
     * SHA-1(key + magic GUID), then Base64 encode.
     */
    public static String generateAcceptKey(String clientKey) {
        try {
            String combined = clientKey + WS_MAGIC;
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] digest = sha1.digest(combined.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new WebSocketException("SHA-1 not available", e);
        }
    }

    /**
     * Build the HTTP 101 Switching Protocols upgrade response.
     */
    public static byte[] buildUpgradeResponse(String clientKey) {
        String acceptKey = generateAcceptKey(clientKey);
        String response = "HTTP/1.1 101 Switching Protocols\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Accept: " + acceptKey + "\r\n" +
                "\r\n";
        return response.getBytes(StandardCharsets.UTF_8);
    }

    // -----------------------------------------------------------------------
    // Frame decoding
    // -----------------------------------------------------------------------

    /**
     * Decode one WebSocket frame from the ByteBuffer.
     * Handles masking and extended payload lengths (16-bit and 64-bit).
     */
    public static WebSocketFrame decodeFrame(ByteBuffer buf) {
        if (buf.remaining() < 2) {
            throw new WebSocketException("Buffer too short for frame header");
        }

        int byte1 = buf.get() & 0xFF;
        int byte2 = buf.get() & 0xFF;

        boolean fin    = (byte1 & 0x80) != 0;
        int opcodeVal  = byte1 & 0x0F;
        boolean masked = (byte2 & 0x80) != 0;
        long payloadLen = byte2 & 0x7F;

        // Extended payload length
        if (payloadLen == 126) {
            if (buf.remaining() < 2) throw new WebSocketException("Buffer too short for 16-bit length");
            payloadLen = ((buf.get() & 0xFF) << 8) | (buf.get() & 0xFF);
        } else if (payloadLen == 127) {
            if (buf.remaining() < 8) throw new WebSocketException("Buffer too short for 64-bit length");
            payloadLen = 0;
            for (int i = 0; i < 8; i++) {
                payloadLen = (payloadLen << 8) | (buf.get() & 0xFF);
            }
        }

        // Masking key
        byte[] maskKey = new byte[4];
        if (masked) {
            if (buf.remaining() < 4) throw new WebSocketException("Buffer too short for mask key");
            buf.get(maskKey);
        }

        // Payload
        int len = (int) payloadLen;
        if (buf.remaining() < len) throw new WebSocketException("Buffer too short for payload");
        byte[] payload = new byte[len];
        buf.get(payload);

        // Unmask
        if (masked) {
            for (int i = 0; i < len; i++) {
                payload[i] ^= maskKey[i % 4];
            }
        }

        return new WebSocketFrame(fin, Opcode.fromValue(opcodeVal), masked, payload);
    }

    // -----------------------------------------------------------------------
    // Frame encoding
    // -----------------------------------------------------------------------

    /**
     * Encode a WebSocket frame to bytes.
     * Server frames are NOT masked (RFC 6455 §5.1).
     */
    public static byte[] encodeFrame(WebSocketFrame frame) {
        byte[] payload = frame.payload() != null ? frame.payload() : new byte[0];
        int len = payload.length;

        // Calculate header size
        int headerSize = 2;
        if (len >= 126 && len <= 65535) {
            headerSize += 2;
        } else if (len > 65535) {
            headerSize += 8;
        }
        // No masking for server frames

        byte[] result = new byte[headerSize + len];
        int pos = 0;

        // Byte 0: FIN + opcode
        result[pos++] = (byte) ((frame.fin() ? 0x80 : 0x00) | (frame.opcode().value & 0x0F));

        // Byte 1: MASK bit (0 for server) + payload length
        if (len < 126) {
            result[pos++] = (byte) len;
        } else if (len <= 65535) {
            result[pos++] = 126;
            result[pos++] = (byte) ((len >> 8) & 0xFF);
            result[pos++] = (byte) (len & 0xFF);
        } else {
            result[pos++] = 127;
            for (int i = 7; i >= 0; i--) {
                result[pos++] = (byte) ((len >> (i * 8)) & 0xFF);
            }
        }

        System.arraycopy(payload, 0, result, pos, len);
        return result;
    }
}
