package flashcache.protocol;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class RESPDecoder {

    /**
     * Decode a RESP-encoded ByteBuffer into a RESPMessage.
     * The buffer's position is advanced past the decoded message.
     */
    public static RESPMessage decode(ByteBuffer input) {
        if (!input.hasRemaining()) {
            throw new RESPDecodeException("Empty input");
        }
        byte type = input.get();
        return switch ((char) type) {
            case '+' -> decodeSimpleString(input);
            case '-' -> decodeError(input);
            case ':' -> decodeInteger(input);
            case '$' -> decodeBulkString(input);
            case '*' -> decodeArray(input);
            default -> throw new RESPDecodeException("Unknown RESP type: " + (char) type);
        };
    }

    // -----------------------------------------------------------------------
    // private helpers
    // -----------------------------------------------------------------------

    private static RESPMessage.SimpleString decodeSimpleString(ByteBuffer input) {
        return new RESPMessage.SimpleString(readLine(input));
    }

    private static RESPMessage.ErrorMessage decodeError(ByteBuffer input) {
        return new RESPMessage.ErrorMessage(readLine(input));
    }

    private static RESPMessage.IntegerMessage decodeInteger(ByteBuffer input) {
        String line = readLine(input);
        try {
            return new RESPMessage.IntegerMessage(Long.parseLong(line));
        } catch (NumberFormatException e) {
            throw new RESPDecodeException("Invalid integer: " + line);
        }
    }

    private static RESPMessage.BulkString decodeBulkString(ByteBuffer input) {
        String lenStr = readLine(input);
        int len;
        try {
            len = Integer.parseInt(lenStr);
        } catch (NumberFormatException e) {
            throw new RESPDecodeException("Invalid bulk string length: " + lenStr);
        }

        if (len == -1) {
            return new RESPMessage.BulkString(null);
        }
        if (len < -1) {
            throw new RESPDecodeException("Invalid bulk string length: " + len);
        }

        byte[] bytes = new byte[len];
        if (input.remaining() < len + 2) {
            throw new RESPDecodeException("Not enough data for bulk string of length " + len);
        }
        input.get(bytes);
        // consume \r\n
        byte cr = input.get();
        byte lf = input.get();
        if (cr != '\r' || lf != '\n') {
            throw new RESPDecodeException("Expected CRLF after bulk string data");
        }
        return new RESPMessage.BulkString(new String(bytes, StandardCharsets.UTF_8));
    }

    private static RESPMessage.ArrayMessage decodeArray(ByteBuffer input) {
        String countStr = readLine(input);
        int count;
        try {
            count = Integer.parseInt(countStr);
        } catch (NumberFormatException e) {
            throw new RESPDecodeException("Invalid array count: " + countStr);
        }

        if (count == -1) {
            // null array — return null elements list (same treatment as null bulk)
            return new RESPMessage.ArrayMessage(null);
        }
        if (count < -1) {
            throw new RESPDecodeException("Invalid array count: " + count);
        }

        List<RESPMessage> elements = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            elements.add(decode(input));
        }
        return new RESPMessage.ArrayMessage(elements);
    }

    /**
     * Read bytes from the buffer up to and including the next \r\n, returning
     * the content before the \r\n as a UTF-8 string.
     */
    private static String readLine(ByteBuffer input) {
        // Collect bytes until we hit \r\n
        int start = input.position();
        int limit = input.limit();
        int end = -1;
        for (int i = start; i < limit - 1; i++) {
            if (input.get(i) == '\r' && input.get(i + 1) == '\n') {
                end = i;
                break;
            }
        }
        if (end == -1) {
            throw new RESPDecodeException("CRLF not found in remaining buffer");
        }
        int lineLen = end - start;
        byte[] bytes = new byte[lineLen];
        input.get(bytes);
        // skip \r\n
        input.get(); // \r
        input.get(); // \n
        return new String(bytes, StandardCharsets.UTF_8);
    }

    // -----------------------------------------------------------------------
    // exception type
    // -----------------------------------------------------------------------

    public static class RESPDecodeException extends RuntimeException {
        public RESPDecodeException(String message) {
            super(message);
        }
    }
}
