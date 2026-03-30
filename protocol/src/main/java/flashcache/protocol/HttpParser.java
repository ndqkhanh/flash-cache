package flashcache.protocol;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Hand-built HTTP/1.1 parser. Parses from a ByteBuffer and encodes HttpResponse to bytes.
 */
public class HttpParser {

    // -----------------------------------------------------------------------
    // Records
    // -----------------------------------------------------------------------

    public record HttpRequest(
            String method,
            String path,
            String version,
            Map<String, String> headers,
            byte[] body
    ) {}

    public record HttpResponse(
            int statusCode,
            String statusText,
            Map<String, String> headers,
            byte[] body
    ) {}

    // -----------------------------------------------------------------------
    // Exception
    // -----------------------------------------------------------------------

    public static class HttpParseException extends RuntimeException {
        public HttpParseException(String message) {
            super(message);
        }
    }

    // -----------------------------------------------------------------------
    // Parse
    // -----------------------------------------------------------------------

    /**
     * Parse an HTTP/1.1 request from the ByteBuffer.
     * The buffer's position is advanced past the parsed data.
     */
    public static HttpRequest parse(ByteBuffer buf) {
        if (!buf.hasRemaining()) {
            throw new HttpParseException("Empty input");
        }

        String requestLine = readLine(buf);
        String[] parts = requestLine.split(" ", 3);
        if (parts.length < 3) {
            throw new HttpParseException("Malformed request line: " + requestLine);
        }
        String method = parts[0];
        String path = parts[1];
        String version = parts[2];

        // Validate method
        if (!method.equals("GET") && !method.equals("POST") &&
                !method.equals("PUT") && !method.equals("DELETE") &&
                !method.equals("HEAD") && !method.equals("OPTIONS") &&
                !method.equals("PATCH")) {
            throw new HttpParseException("Unknown HTTP method: " + method);
        }

        // Parse headers
        Map<String, String> headers = new LinkedHashMap<>();
        while (buf.hasRemaining()) {
            String line = readLine(buf);
            if (line.isEmpty()) {
                break; // blank line = end of headers
            }
            int colon = line.indexOf(':');
            if (colon < 0) {
                throw new HttpParseException("Malformed header line: " + line);
            }
            String name = line.substring(0, colon).trim().toLowerCase();
            String value = line.substring(colon + 1).trim();
            headers.put(name, value);
        }

        // Parse body based on Content-Length
        byte[] body = new byte[0];
        if (headers.containsKey("content-length")) {
            int len;
            try {
                len = Integer.parseInt(headers.get("content-length").trim());
            } catch (NumberFormatException e) {
                throw new HttpParseException("Invalid Content-Length: " + headers.get("content-length"));
            }
            if (len > 0) {
                if (buf.remaining() < len) {
                    throw new HttpParseException("Body shorter than Content-Length declares");
                }
                body = new byte[len];
                buf.get(body);
            }
        }

        return new HttpRequest(method, path, version, headers, body);
    }

    // -----------------------------------------------------------------------
    // Encode
    // -----------------------------------------------------------------------

    /**
     * Encode an HttpResponse to a byte array.
     * Automatically adds Content-Length if body is non-empty and header not present.
     */
    public static byte[] encode(HttpResponse response) {
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ")
          .append(response.statusCode())
          .append(' ')
          .append(response.statusText())
          .append("\r\n");

        Map<String, String> headers = new LinkedHashMap<>(
                response.headers() != null ? response.headers() : Map.of());

        byte[] body = response.body() != null ? response.body() : new byte[0];

        // Auto-add Content-Length
        if (!headers.containsKey("content-length") && !headers.containsKey("Content-Length")) {
            headers.put("Content-Length", String.valueOf(body.length));
        }

        for (Map.Entry<String, String> entry : headers.entrySet()) {
            sb.append(entry.getKey()).append(": ").append(entry.getValue()).append("\r\n");
        }
        sb.append("\r\n");

        byte[] headerBytes = sb.toString().getBytes(StandardCharsets.UTF_8);
        byte[] result = new byte[headerBytes.length + body.length];
        System.arraycopy(headerBytes, 0, result, 0, headerBytes.length);
        System.arraycopy(body, 0, result, headerBytes.length, body.length);
        return result;
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private static String readLine(ByteBuffer buf) {
        int start = buf.position();
        int limit = buf.limit();
        int end = -1;
        for (int i = start; i < limit - 1; i++) {
            if (buf.get(i) == '\r' && buf.get(i + 1) == '\n') {
                end = i;
                break;
            }
        }
        if (end == -1) {
            // Try LF-only fallback
            for (int i = start; i < limit; i++) {
                if (buf.get(i) == '\n') {
                    end = i;
                    byte[] bytes = new byte[end - start];
                    buf.get(bytes);
                    buf.get(); // consume \n
                    return new String(bytes, StandardCharsets.UTF_8).stripTrailing();
                }
            }
            throw new HttpParseException("Line terminator not found in buffer");
        }
        byte[] bytes = new byte[end - start];
        buf.get(bytes);
        buf.get(); // \r
        buf.get(); // \n
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
