package flashcache.protocol;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Simplified HTTP/2 framing + HPACK header compression.
 * Implements the 9-byte frame header, key frame types, and a static HPACK table.
 */
public class Http2Handler {

    // Connection preface sent by clients
    public static final String CONNECTION_PREFACE = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n";

    // -----------------------------------------------------------------------
    // Frame type enum
    // -----------------------------------------------------------------------

    public enum FrameType {
        DATA(0x0),
        HEADERS(0x1),
        PRIORITY(0x2),
        RST_STREAM(0x3),
        SETTINGS(0x4),
        PUSH_PROMISE(0x5),
        PING(0x6),
        GOAWAY(0x7),
        WINDOW_UPDATE(0x8),
        CONTINUATION(0x9);

        public final int value;

        FrameType(int value) { this.value = value; }

        public static FrameType fromValue(int v) {
            for (FrameType ft : values()) {
                if (ft.value == v) return ft;
            }
            throw new Http2Exception("Unknown frame type: " + v);
        }
    }

    // Common flags
    public static final int FLAG_END_STREAM  = 0x1;
    public static final int FLAG_END_HEADERS = 0x4;
    public static final int FLAG_ACK         = 0x1;
    public static final int FLAG_PADDED      = 0x8;
    public static final int FLAG_PRIORITY    = 0x20;

    // -----------------------------------------------------------------------
    // Frame record
    // -----------------------------------------------------------------------

    public record Http2Frame(
            int length,       // 24-bit payload length
            FrameType type,
            int flags,
            int streamId,     // 31-bit stream identifier
            byte[] payload
    ) {}

    // -----------------------------------------------------------------------
    // Exception
    // -----------------------------------------------------------------------

    public static class Http2Exception extends RuntimeException {
        public Http2Exception(String message) { super(message); }
    }

    // -----------------------------------------------------------------------
    // Frame decode / encode
    // -----------------------------------------------------------------------

    /**
     * Decode one HTTP/2 frame from the ByteBuffer.
     * Expects at least 9 bytes (frame header) + payload.
     */
    public static Http2Frame decodeFrame(ByteBuffer buf) {
        if (buf.remaining() < 9) {
            throw new Http2Exception("Buffer too short for frame header: " + buf.remaining());
        }
        // 3-byte length
        int length = ((buf.get() & 0xFF) << 16)
                   | ((buf.get() & 0xFF) << 8)
                   |  (buf.get() & 0xFF);
        int typeVal = buf.get() & 0xFF;
        int flags   = buf.get() & 0xFF;
        // 4-byte stream id, MSB reserved (ignore)
        int streamId = ((buf.get() & 0x7F) << 24)
                     | ((buf.get() & 0xFF) << 16)
                     | ((buf.get() & 0xFF) << 8)
                     |  (buf.get() & 0xFF);

        if (buf.remaining() < length) {
            throw new Http2Exception("Buffer too short for payload: expected " + length + ", got " + buf.remaining());
        }
        byte[] payload = new byte[length];
        buf.get(payload);

        return new Http2Frame(length, FrameType.fromValue(typeVal), flags, streamId, payload);
    }

    /**
     * Encode an HTTP/2 frame into a byte array (9-byte header + payload).
     */
    public static byte[] encodeFrame(Http2Frame frame) {
        byte[] payload = frame.payload() != null ? frame.payload() : new byte[0];
        int length = payload.length;
        byte[] result = new byte[9 + length];
        // 3-byte length
        result[0] = (byte) ((length >> 16) & 0xFF);
        result[1] = (byte) ((length >> 8)  & 0xFF);
        result[2] = (byte) (length & 0xFF);
        // type
        result[3] = (byte) (frame.type().value & 0xFF);
        // flags
        result[4] = (byte) (frame.flags() & 0xFF);
        // 4-byte stream id
        result[5] = (byte) ((frame.streamId() >> 24) & 0x7F);
        result[6] = (byte) ((frame.streamId() >> 16) & 0xFF);
        result[7] = (byte) ((frame.streamId() >> 8)  & 0xFF);
        result[8] = (byte) (frame.streamId() & 0xFF);
        System.arraycopy(payload, 0, result, 9, length);
        return result;
    }

    // -----------------------------------------------------------------------
    // Connection preface check
    // -----------------------------------------------------------------------

    public static boolean isConnectionPreface(ByteBuffer buf) {
        byte[] prefaceBytes = CONNECTION_PREFACE.getBytes(StandardCharsets.UTF_8);
        if (buf.remaining() < prefaceBytes.length) return false;
        int savedPos = buf.position();
        byte[] sample = new byte[prefaceBytes.length];
        buf.get(sample);
        buf.position(savedPos);
        return Arrays.equals(sample, prefaceBytes);
    }

    // -----------------------------------------------------------------------
    // HPACK static table (RFC 7541 Appendix A, entries 1-20)
    // -----------------------------------------------------------------------

    // Each entry: {name, value} — index is 1-based
    private static final String[][] STATIC_TABLE = {
        null,                                           // index 0 unused
        {":authority", ""},                             // 1
        {":method", "GET"},                             // 2
        {":method", "POST"},                            // 3
        {":path", "/"},                                 // 4
        {":path", "/index.html"},                       // 5
        {":scheme", "http"},                            // 6
        {":scheme", "https"},                           // 7
        {":status", "200"},                             // 8
        {":status", "204"},                             // 9
        {":status", "206"},                             // 10
        {":status", "304"},                             // 11
        {":status", "400"},                             // 12
        {":status", "404"},                             // 13
        {":status", "500"},                             // 14
        {"accept-charset", ""},                         // 15
        {"accept-encoding", "gzip, deflate"},           // 16
        {"accept-language", ""},                        // 17
        {"accept-ranges", ""},                          // 18
        {"accept", ""},                                 // 19
        {"access-control-allow-origin", ""},            // 20
    };

    // -----------------------------------------------------------------------
    // HpackEncoder (simplified: uses indexed headers when possible,
    // otherwise literal without indexing)
    // -----------------------------------------------------------------------

    public static class HpackEncoder {

        /**
         * Encode a map of headers using HPACK simplified static table.
         */
        public static byte[] encode(Map<String, String> headers) {
            ByteBuffer out = ByteBuffer.allocate(4096);
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                String name  = entry.getKey().toLowerCase();
                String value = entry.getValue();
                int idx = findStaticIndex(name, value);
                if (idx > 0) {
                    // Indexed header field representation (bit pattern 1xxxxxxx)
                    writeInteger(out, idx, 7, 0x80);
                } else {
                    int nameIdx = findStaticNameIndex(name);
                    if (nameIdx > 0) {
                        // Literal with name indexed (bit pattern 0100xxxx)
                        writeInteger(out, nameIdx, 4, 0x40);
                    } else {
                        // Literal with new name (bit pattern 01000000, nameIdx=0 encoded in prefix)
                        out.put((byte) 0x40);
                        writeLiteralString(out, name);
                    }
                    writeLiteralString(out, value);
                }
            }
            out.flip();
            byte[] result = new byte[out.remaining()];
            out.get(result);
            return result;
        }

        private static void writeInteger(ByteBuffer out, int value, int prefixBits, int firstBytePrefix) {
            int maxPrefix = (1 << prefixBits) - 1;
            if (value < maxPrefix) {
                out.put((byte) (firstBytePrefix | value));
            } else {
                out.put((byte) (firstBytePrefix | maxPrefix));
                value -= maxPrefix;
                while (value >= 128) {
                    out.put((byte) ((value % 128) + 128));
                    value /= 128;
                }
                out.put((byte) value);
            }
        }

        private static void writeLiteralString(ByteBuffer out, String s) {
            byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
            // No Huffman encoding (bit 7 = 0)
            writeInteger(out, bytes.length, 7, 0x00);
            out.put(bytes);
        }

        private static int findStaticIndex(String name, String value) {
            for (int i = 1; i < STATIC_TABLE.length; i++) {
                if (STATIC_TABLE[i] != null &&
                        STATIC_TABLE[i][0].equals(name) &&
                        STATIC_TABLE[i][1].equals(value)) {
                    return i;
                }
            }
            return -1;
        }

        private static int findStaticNameIndex(String name) {
            for (int i = 1; i < STATIC_TABLE.length; i++) {
                if (STATIC_TABLE[i] != null && STATIC_TABLE[i][0].equals(name)) {
                    return i;
                }
            }
            return -1;
        }
    }

    // -----------------------------------------------------------------------
    // HpackDecoder (simplified: handles indexed and literal representations)
    // -----------------------------------------------------------------------

    public static class HpackDecoder {

        /**
         * Decode HPACK-encoded header block fragment.
         */
        public static Map<String, String> decode(byte[] data) {
            ByteBuffer buf = ByteBuffer.wrap(data);
            Map<String, String> headers = new LinkedHashMap<>();
            while (buf.hasRemaining()) {
                int b = buf.get() & 0xFF;
                if ((b & 0x80) != 0) {
                    // Indexed header field (1xxxxxxx)
                    int idx = decodeIntegerContinuation(b & 0x7F, 7, buf);
                    String[] entry = staticTableLookup(idx);
                    headers.put(entry[0], entry[1]);
                } else if ((b & 0x40) != 0) {
                    // Literal with incremental indexing (01xxxxxx)
                    int nameIdx = decodeIntegerContinuation(b & 0x3F, 6, buf);
                    String name;
                    if (nameIdx == 0) {
                        name = readLiteralString(buf);
                    } else {
                        name = staticTableLookup(nameIdx)[0];
                    }
                    String value = readLiteralString(buf);
                    headers.put(name, value);
                } else if ((b & 0x20) != 0) {
                    // Dynamic table size update — skip
                    decodeIntegerContinuation(b & 0x1F, 5, buf);
                } else {
                    // Literal without indexing (0000xxxx) or never indexed (0001xxxx)
                    int nameIdx = decodeIntegerContinuation(b & 0x0F, 4, buf);
                    String name;
                    if (nameIdx == 0) {
                        name = readLiteralString(buf);
                    } else {
                        name = staticTableLookup(nameIdx)[0];
                    }
                    String value = readLiteralString(buf);
                    headers.put(name, value);
                }
            }
            return headers;
        }

        private static int decodeIntegerContinuation(int firstBits, int prefixBits, ByteBuffer buf) {
            int maxPrefix = (1 << prefixBits) - 1;
            if (firstBits < maxPrefix) {
                return firstBits;
            }
            int value = firstBits;
            int multiplier = 1;
            while (buf.hasRemaining()) {
                int next = buf.get() & 0xFF;
                value += (next & 0x7F) * multiplier;
                multiplier *= 128;
                if ((next & 0x80) == 0) break;
            }
            return value;
        }

        private static String readLiteralString(ByteBuffer buf) {
            int b = buf.get() & 0xFF;
            boolean huffman = (b & 0x80) != 0;
            int len = decodeIntegerContinuation(b & 0x7F, 7, buf);
            byte[] bytes = new byte[len];
            buf.get(bytes);
            // We don't support Huffman decoding; assume plain text
            if (huffman) {
                throw new Http2Exception("Huffman decoding not supported in simplified HPACK");
            }
            return new String(bytes, StandardCharsets.UTF_8);
        }

        private static String[] staticTableLookup(int idx) {
            if (idx < 1 || idx >= STATIC_TABLE.length || STATIC_TABLE[idx] == null) {
                throw new Http2Exception("Invalid static table index: " + idx);
            }
            return STATIC_TABLE[idx];
        }
    }
}
