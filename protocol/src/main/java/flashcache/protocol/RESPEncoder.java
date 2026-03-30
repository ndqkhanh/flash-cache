package flashcache.protocol;

import java.nio.charset.StandardCharsets;
import java.util.List;

public class RESPEncoder {

    private static final byte[] CRLF = "\r\n".getBytes(StandardCharsets.UTF_8);

    // -----------------------------------------------------------------------
    // public API
    // -----------------------------------------------------------------------

    public static byte[] encodeSimpleString(String value) {
        return concat("+", value, "\r\n");
    }

    public static byte[] encodeError(String message) {
        return concat("-", message, "\r\n");
    }

    public static byte[] encodeInteger(long value) {
        return (":" + value + "\r\n").getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] encodeBulkString(String value) {
        if (value == null) {
            return encodeNullBulkString();
        }
        byte[] data = value.getBytes(StandardCharsets.UTF_8);
        byte[] header = ("$" + data.length + "\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] result = new byte[header.length + data.length + 2];
        System.arraycopy(header, 0, result, 0, header.length);
        System.arraycopy(data, 0, result, header.length, data.length);
        result[header.length + data.length] = '\r';
        result[header.length + data.length + 1] = '\n';
        return result;
    }

    public static byte[] encodeNullBulkString() {
        return "$-1\r\n".getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] encodeArray(List<RESPMessage> elements) {
        if (elements == null) {
            return "*-1\r\n".getBytes(StandardCharsets.UTF_8);
        }
        byte[] header = ("*" + elements.size() + "\r\n").getBytes(StandardCharsets.UTF_8);
        // encode each element
        byte[][] encodedElements = new byte[elements.size()][];
        int totalSize = header.length;
        for (int i = 0; i < elements.size(); i++) {
            encodedElements[i] = encodeMessage(elements.get(i));
            totalSize += encodedElements[i].length;
        }
        byte[] result = new byte[totalSize];
        int offset = 0;
        System.arraycopy(header, 0, result, offset, header.length);
        offset += header.length;
        for (byte[] encoded : encodedElements) {
            System.arraycopy(encoded, 0, result, offset, encoded.length);
            offset += encoded.length;
        }
        return result;
    }

    /**
     * Encode any RESPMessage to its wire format.
     */
    public static byte[] encodeMessage(RESPMessage message) {
        return switch (message) {
            case RESPMessage.SimpleString ss -> encodeSimpleString(ss.value());
            case RESPMessage.ErrorMessage err -> encodeError(err.message());
            case RESPMessage.IntegerMessage i -> encodeInteger(i.value());
            case RESPMessage.BulkString bs -> encodeBulkString(bs.value());
            case RESPMessage.ArrayMessage arr -> encodeArray(arr.elements());
        };
    }

    // -----------------------------------------------------------------------
    // private helpers
    // -----------------------------------------------------------------------

    private static byte[] concat(String prefix, String body, String suffix) {
        byte[] p = prefix.getBytes(StandardCharsets.UTF_8);
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        byte[] s = suffix.getBytes(StandardCharsets.UTF_8);
        byte[] result = new byte[p.length + b.length + s.length];
        System.arraycopy(p, 0, result, 0, p.length);
        System.arraycopy(b, 0, result, p.length, b.length);
        System.arraycopy(s, 0, result, p.length + b.length, s.length);
        return result;
    }
}
