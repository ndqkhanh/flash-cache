package flashcache.protocol;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.*;

class WebSocketHandlerTest {

    // -----------------------------------------------------------------------
    // Handshake / accept key
    // -----------------------------------------------------------------------

    @Test
    void generateAcceptKey_rfcTestVector() {
        // RFC 6455 §1.3 test vector
        String clientKey = "dGhlIHNhbXBsZSBub25jZQ==";
        String expected = "s3pPLMBiTxaQ9kYGzzhZRbK+xOo=";
        assertThat(WebSocketHandler.generateAcceptKey(clientKey)).isEqualTo(expected);
    }

    @Test
    void buildUpgradeResponse_containsRequiredHeaders() {
        String clientKey = "dGhlIHNhbXBsZSBub25jZQ==";
        byte[] resp = WebSocketHandler.buildUpgradeResponse(clientKey);
        String text = new String(resp, StandardCharsets.UTF_8);
        assertThat(text).contains("HTTP/1.1 101 Switching Protocols");
        assertThat(text).containsIgnoringCase("Upgrade: websocket");
        assertThat(text).containsIgnoringCase("Connection: Upgrade");
        assertThat(text).contains("Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=");
    }

    // -----------------------------------------------------------------------
    // Decode tests
    // -----------------------------------------------------------------------

    @Test
    void decodeUnmaskedTextFrame() {
        // FIN=1, opcode=TEXT(1), no mask, payload="Hi"
        byte[] raw = {
            (byte) 0x81,  // FIN + TEXT
            0x02,         // no mask, length=2
            'H', 'i'
        };
        WebSocketHandler.WebSocketFrame frame = WebSocketHandler.decodeFrame(ByteBuffer.wrap(raw));
        assertThat(frame.fin()).isTrue();
        assertThat(frame.opcode()).isEqualTo(WebSocketHandler.Opcode.TEXT);
        assertThat(frame.masked()).isFalse();
        assertThat(new String(frame.payload(), StandardCharsets.UTF_8)).isEqualTo("Hi");
    }

    @Test
    void decodeMaskedTextFrame() {
        // FIN=1, opcode=TEXT(1), masked, payload="Hello" with mask 0x37fa213d
        // Masked payload from RFC 6455 §5.7 example
        byte[] mask = {0x37, (byte) 0xfa, 0x21, 0x3d};
        byte[] text = "Hello".getBytes(StandardCharsets.UTF_8);
        byte[] maskedPayload = new byte[text.length];
        for (int i = 0; i < text.length; i++) {
            maskedPayload[i] = (byte) (text[i] ^ mask[i % 4]);
        }
        byte[] raw = new byte[2 + 4 + maskedPayload.length];
        raw[0] = (byte) 0x81; // FIN + TEXT
        raw[1] = (byte) (0x80 | maskedPayload.length); // MASK + length
        raw[2] = mask[0]; raw[3] = mask[1]; raw[4] = mask[2]; raw[5] = mask[3];
        System.arraycopy(maskedPayload, 0, raw, 6, maskedPayload.length);

        WebSocketHandler.WebSocketFrame frame = WebSocketHandler.decodeFrame(ByteBuffer.wrap(raw));
        assertThat(frame.masked()).isTrue();
        assertThat(new String(frame.payload(), StandardCharsets.UTF_8)).isEqualTo("Hello");
    }

    @Test
    void decodeFrameWith126ByteExtendedLength() {
        byte[] payload = new byte[130];
        for (int i = 0; i < payload.length; i++) payload[i] = (byte) i;
        ByteBuffer buf = ByteBuffer.allocate(2 + 2 + payload.length);
        buf.put((byte) 0x82); // FIN + BINARY
        buf.put((byte) 126);  // extended 16-bit length follows
        buf.put((byte) 0);
        buf.put((byte) 130);  // length = 130
        buf.put(payload);
        buf.flip();

        WebSocketHandler.WebSocketFrame frame = WebSocketHandler.decodeFrame(buf);
        assertThat(frame.opcode()).isEqualTo(WebSocketHandler.Opcode.BINARY);
        assertThat(frame.payload()).hasSize(130);
    }

    @Test
    void decodePingFrame() {
        byte[] raw = {(byte) 0x89, 0x00}; // FIN + PING, no payload
        WebSocketHandler.WebSocketFrame frame = WebSocketHandler.decodeFrame(ByteBuffer.wrap(raw));
        assertThat(frame.opcode()).isEqualTo(WebSocketHandler.Opcode.PING);
        assertThat(frame.fin()).isTrue();
        assertThat(frame.payload()).isEmpty();
    }

    @Test
    void decodeCloseFrame() {
        byte[] raw = {(byte) 0x88, 0x00}; // FIN + CLOSE, no payload
        WebSocketHandler.WebSocketFrame frame = WebSocketHandler.decodeFrame(ByteBuffer.wrap(raw));
        assertThat(frame.opcode()).isEqualTo(WebSocketHandler.Opcode.CLOSE);
    }

    // -----------------------------------------------------------------------
    // Encode tests
    // -----------------------------------------------------------------------

    @Test
    void encodeTextFrame_serverNoMask() {
        byte[] payload = "Hello".getBytes(StandardCharsets.UTF_8);
        WebSocketHandler.WebSocketFrame frame = new WebSocketHandler.WebSocketFrame(
                true, WebSocketHandler.Opcode.TEXT, false, payload);
        byte[] encoded = WebSocketHandler.encodeFrame(frame);
        // First byte: FIN(1) + TEXT(1) = 0x81
        assertThat(encoded[0] & 0xFF).isEqualTo(0x81);
        // Second byte: no mask bit, length=5
        assertThat(encoded[1] & 0xFF).isEqualTo(5);
        assertThat(encoded[1] & 0x80).isEqualTo(0); // no mask
        assertThat(new String(encoded, 2, 5, StandardCharsets.UTF_8)).isEqualTo("Hello");
    }

    @Test
    void encodePongFrame() {
        WebSocketHandler.WebSocketFrame frame = new WebSocketHandler.WebSocketFrame(
                true, WebSocketHandler.Opcode.PONG, false, new byte[0]);
        byte[] encoded = WebSocketHandler.encodeFrame(frame);
        assertThat(encoded[0] & 0xFF).isEqualTo(0x8A); // FIN + PONG(0xA)
        assertThat(encoded[1] & 0xFF).isEqualTo(0);    // no payload
    }

    @Test
    void roundTrip_encodeDecodeMustMatch() {
        byte[] payload = "round-trip".getBytes(StandardCharsets.UTF_8);
        WebSocketHandler.WebSocketFrame original = new WebSocketHandler.WebSocketFrame(
                true, WebSocketHandler.Opcode.TEXT, false, payload);
        byte[] encoded = WebSocketHandler.encodeFrame(original);
        WebSocketHandler.WebSocketFrame decoded = WebSocketHandler.decodeFrame(ByteBuffer.wrap(encoded));
        assertThat(decoded.fin()).isEqualTo(original.fin());
        assertThat(decoded.opcode()).isEqualTo(original.opcode());
        assertThat(decoded.payload()).isEqualTo(original.payload());
    }

    @Test
    void encodeFrameWithExtendedLength() {
        byte[] payload = new byte[200];
        for (int i = 0; i < payload.length; i++) payload[i] = (byte) (i % 256);
        WebSocketHandler.WebSocketFrame frame = new WebSocketHandler.WebSocketFrame(
                true, WebSocketHandler.Opcode.BINARY, false, payload);
        byte[] encoded = WebSocketHandler.encodeFrame(frame);
        // Second byte should be 126 (16-bit extended length indicator)
        assertThat(encoded[1] & 0x7F).isEqualTo(126);
        // Decode back
        WebSocketHandler.WebSocketFrame decoded = WebSocketHandler.decodeFrame(ByteBuffer.wrap(encoded));
        assertThat(decoded.payload()).isEqualTo(payload);
    }
}
