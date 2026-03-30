package flashcache.protocol;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class HttpParserTest {

    private static ByteBuffer buf(String s) {
        return ByteBuffer.wrap(s.getBytes(StandardCharsets.UTF_8));
    }

    // -----------------------------------------------------------------------
    // Parse tests
    // -----------------------------------------------------------------------

    @Test
    void parseGet_noBody() {
        String raw = "GET /cache/mykey HTTP/1.1\r\nHost: localhost\r\n\r\n";
        HttpParser.HttpRequest req = HttpParser.parse(buf(raw));
        assertThat(req.method()).isEqualTo("GET");
        assertThat(req.path()).isEqualTo("/cache/mykey");
        assertThat(req.version()).isEqualTo("HTTP/1.1");
        assertThat(req.headers()).containsEntry("host", "localhost");
        assertThat(req.body()).isEmpty();
    }

    @Test
    void parsePost_withBody() {
        String bodyStr = "hello";
        String raw = "POST /cache/key HTTP/1.1\r\nHost: localhost\r\nContent-Length: 5\r\n\r\nhello";
        HttpParser.HttpRequest req = HttpParser.parse(buf(raw));
        assertThat(req.method()).isEqualTo("POST");
        assertThat(req.path()).isEqualTo("/cache/key");
        assertThat(new String(req.body(), StandardCharsets.UTF_8)).isEqualTo("hello");
    }

    @Test
    void parsePut_withJsonBody() {
        String body = "{\"value\":42}";
        String raw = "PUT /cache/json HTTP/1.1\r\nContent-Type: application/json\r\nContent-Length: " +
                body.length() + "\r\n\r\n" + body;
        HttpParser.HttpRequest req = HttpParser.parse(buf(raw));
        assertThat(req.method()).isEqualTo("PUT");
        assertThat(new String(req.body(), StandardCharsets.UTF_8)).isEqualTo(body);
        assertThat(req.headers()).containsEntry("content-type", "application/json");
    }

    @Test
    void parseDelete_noBody() {
        String raw = "DELETE /cache/mykey HTTP/1.1\r\nHost: localhost\r\n\r\n";
        HttpParser.HttpRequest req = HttpParser.parse(buf(raw));
        assertThat(req.method()).isEqualTo("DELETE");
        assertThat(req.path()).isEqualTo("/cache/mykey");
        assertThat(req.body()).isEmpty();
    }

    @Test
    void parseRequest_multipleHeaders() {
        String raw = "GET / HTTP/1.1\r\nHost: example.com\r\nAccept: */*\r\nX-Custom: value\r\n\r\n";
        HttpParser.HttpRequest req = HttpParser.parse(buf(raw));
        assertThat(req.headers()).containsEntry("host", "example.com");
        assertThat(req.headers()).containsEntry("accept", "*/*");
        assertThat(req.headers()).containsEntry("x-custom", "value");
    }

    @Test
    void parseRequest_contentLengthBody() {
        String body = "world";
        String raw = "POST /data HTTP/1.1\r\nContent-Length: 5\r\n\r\nworld";
        HttpParser.HttpRequest req = HttpParser.parse(buf(raw));
        assertThat(req.body()).hasSize(5);
        assertThat(new String(req.body(), StandardCharsets.UTF_8)).isEqualTo("world");
    }

    @Test
    void parseHttp10Request() {
        String raw = "GET /index HTTP/1.0\r\nHost: localhost\r\n\r\n";
        HttpParser.HttpRequest req = HttpParser.parse(buf(raw));
        assertThat(req.version()).isEqualTo("HTTP/1.0");
        assertThat(req.method()).isEqualTo("GET");
    }

    // -----------------------------------------------------------------------
    // Encode tests
    // -----------------------------------------------------------------------

    @Test
    void encode200Ok() {
        HttpParser.HttpResponse resp = new HttpParser.HttpResponse(200, "OK", Map.of(), new byte[0]);
        String encoded = new String(HttpParser.encode(resp), StandardCharsets.UTF_8);
        assertThat(encoded).startsWith("HTTP/1.1 200 OK\r\n");
        assertThat(encoded).contains("Content-Length: 0");
        assertThat(encoded).endsWith("\r\n\r\n");
    }

    @Test
    void encode404NotFound() {
        HttpParser.HttpResponse resp = new HttpParser.HttpResponse(404, "Not Found", Map.of(), new byte[0]);
        String encoded = new String(HttpParser.encode(resp), StandardCharsets.UTF_8);
        assertThat(encoded).startsWith("HTTP/1.1 404 Not Found\r\n");
    }

    @Test
    void encodeResponseWithJsonBody() {
        String json = "{\"key\":\"val\"}";
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        Map<String, String> headers = Map.of("Content-Type", "application/json");
        HttpParser.HttpResponse resp = new HttpParser.HttpResponse(200, "OK", headers, body);
        String encoded = new String(HttpParser.encode(resp), StandardCharsets.UTF_8);
        assertThat(encoded).contains("Content-Type: application/json");
        assertThat(encoded).endsWith(json);
    }

    @Test
    void encodeAutoAddsContentLength() {
        byte[] body = "test".getBytes(StandardCharsets.UTF_8);
        HttpParser.HttpResponse resp = new HttpParser.HttpResponse(200, "OK", Map.of(), body);
        String encoded = new String(HttpParser.encode(resp), StandardCharsets.UTF_8);
        assertThat(encoded).contains("Content-Length: 4");
    }

    @Test
    void encodeParseCycle() {
        byte[] body = "data".getBytes(StandardCharsets.UTF_8);
        HttpParser.HttpResponse resp = new HttpParser.HttpResponse(201, "Created", Map.of(), body);
        byte[] encoded = HttpParser.encode(resp);
        assertThat(new String(encoded, StandardCharsets.UTF_8)).contains("HTTP/1.1 201 Created");
        assertThat(new String(encoded, StandardCharsets.UTF_8)).endsWith("data");
    }

    // -----------------------------------------------------------------------
    // Error cases
    // -----------------------------------------------------------------------

    @Test
    void malformedRequest_throwsException() {
        assertThatThrownBy(() -> HttpParser.parse(buf("NOT A REQUEST\r\n\r\n")))
                .isInstanceOf(HttpParser.HttpParseException.class);
    }

    @Test
    void emptyInput_throwsException() {
        assertThatThrownBy(() -> HttpParser.parse(buf("")))
                .isInstanceOf(HttpParser.HttpParseException.class);
    }
}
