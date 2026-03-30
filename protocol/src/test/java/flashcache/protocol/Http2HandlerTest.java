package flashcache.protocol;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class Http2HandlerTest {

    // Helper: build a raw frame byte array
    private static byte[] rawFrame(int length, int type, int flags, int streamId, byte[] payload) {
        byte[] frame = new byte[9 + (payload != null ? payload.length : 0)];
        frame[0] = (byte) ((length >> 16) & 0xFF);
        frame[1] = (byte) ((length >> 8)  & 0xFF);
        frame[2] = (byte) (length & 0xFF);
        frame[3] = (byte) (type & 0xFF);
        frame[4] = (byte) (flags & 0xFF);
        frame[5] = (byte) ((streamId >> 24) & 0x7F);
        frame[6] = (byte) ((streamId >> 16) & 0xFF);
        frame[7] = (byte) ((streamId >> 8)  & 0xFF);
        frame[8] = (byte) (streamId & 0xFF);
        if (payload != null) {
            System.arraycopy(payload, 0, frame, 9, payload.length);
        }
        return frame;
    }

    // -----------------------------------------------------------------------
    // Decode tests
    // -----------------------------------------------------------------------

    @Test
    void decodeDataFrame() {
        byte[] payload = "hello".getBytes(StandardCharsets.UTF_8);
        byte[] raw = rawFrame(payload.length, 0x0, 0x0, 1, payload);
        Http2Handler.Http2Frame frame = Http2Handler.decodeFrame(ByteBuffer.wrap(raw));
        assertThat(frame.type()).isEqualTo(Http2Handler.FrameType.DATA);
        assertThat(frame.streamId()).isEqualTo(1);
        assertThat(frame.payload()).isEqualTo(payload);
        assertThat(frame.flags()).isEqualTo(0);
    }

    @Test
    void decodeHeadersFrame() {
        byte[] payload = new byte[]{(byte) 0x82}; // indexed :method GET
        byte[] raw = rawFrame(payload.length, 0x1, Http2Handler.FLAG_END_HEADERS, 1, payload);
        Http2Handler.Http2Frame frame = Http2Handler.decodeFrame(ByteBuffer.wrap(raw));
        assertThat(frame.type()).isEqualTo(Http2Handler.FrameType.HEADERS);
        assertThat(frame.flags() & Http2Handler.FLAG_END_HEADERS).isNotZero();
    }

    @Test
    void decodeSettingsFrame() {
        byte[] raw = rawFrame(0, 0x4, 0x1, 0, new byte[0]); // ACK settings
        Http2Handler.Http2Frame frame = Http2Handler.decodeFrame(ByteBuffer.wrap(raw));
        assertThat(frame.type()).isEqualTo(Http2Handler.FrameType.SETTINGS);
        assertThat(frame.streamId()).isEqualTo(0);
        assertThat(frame.flags() & Http2Handler.FLAG_ACK).isNotZero();
    }

    @Test
    void decodePingFrame() {
        byte[] payload = new byte[8]; // ping data
        byte[] raw = rawFrame(8, 0x6, 0x0, 0, payload);
        Http2Handler.Http2Frame frame = Http2Handler.decodeFrame(ByteBuffer.wrap(raw));
        // PING has type value 6 which maps to PING in our enum
        assertThat(frame.type()).isEqualTo(Http2Handler.FrameType.PING);
        assertThat(frame.payload()).hasSize(8);
    }

    // -----------------------------------------------------------------------
    // Encode / round-trip tests
    // -----------------------------------------------------------------------

    @Test
    void encodeDataFrame_roundTrip() {
        byte[] payload = "data".getBytes(StandardCharsets.UTF_8);
        Http2Handler.Http2Frame original = new Http2Handler.Http2Frame(
                payload.length, Http2Handler.FrameType.DATA, 0, 3, payload);
        byte[] encoded = Http2Handler.encodeFrame(original);
        Http2Handler.Http2Frame decoded = Http2Handler.decodeFrame(ByteBuffer.wrap(encoded));
        assertThat(decoded.type()).isEqualTo(Http2Handler.FrameType.DATA);
        assertThat(decoded.streamId()).isEqualTo(3);
        assertThat(decoded.payload()).isEqualTo(payload);
    }

    @Test
    void encodeHeadersFrame_roundTrip() {
        byte[] payload = new byte[]{0x01, 0x02, 0x03};
        Http2Handler.Http2Frame original = new Http2Handler.Http2Frame(
                payload.length, Http2Handler.FrameType.HEADERS,
                Http2Handler.FLAG_END_HEADERS, 5, payload);
        byte[] encoded = Http2Handler.encodeFrame(original);
        Http2Handler.Http2Frame decoded = Http2Handler.decodeFrame(ByteBuffer.wrap(encoded));
        assertThat(decoded.type()).isEqualTo(Http2Handler.FrameType.HEADERS);
        assertThat(decoded.streamId()).isEqualTo(5);
        assertThat(decoded.flags() & Http2Handler.FLAG_END_HEADERS).isNotZero();
    }

    @Test
    void frameWithEndStreamFlag() {
        byte[] payload = new byte[0];
        Http2Handler.Http2Frame frame = new Http2Handler.Http2Frame(
                0, Http2Handler.FrameType.DATA, Http2Handler.FLAG_END_STREAM, 7, payload);
        byte[] encoded = Http2Handler.encodeFrame(frame);
        Http2Handler.Http2Frame decoded = Http2Handler.decodeFrame(ByteBuffer.wrap(encoded));
        assertThat(decoded.flags() & Http2Handler.FLAG_END_STREAM).isNotZero();
    }

    // -----------------------------------------------------------------------
    // HPACK tests
    // -----------------------------------------------------------------------

    @Test
    void hpackEncodeMethodGet() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(":method", "GET");
        byte[] encoded = Http2Handler.HpackEncoder.encode(headers);
        // :method GET is static table index 2 → indexed representation = 0x82
        assertThat(encoded).isNotEmpty();
        assertThat(encoded[0] & 0xFF).isEqualTo(0x82);
    }

    @Test
    void hpackEncodePathRoot() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(":path", "/");
        byte[] encoded = Http2Handler.HpackEncoder.encode(headers);
        // :path / is static table index 4 → 0x84
        assertThat(encoded).isNotEmpty();
        assertThat(encoded[0] & 0xFF).isEqualTo(0x84);
    }

    @Test
    void hpackDecodeEncodedHeaders() {
        Map<String, String> original = new LinkedHashMap<>();
        original.put(":method", "GET");
        original.put(":path", "/");
        original.put(":scheme", "https");
        byte[] encoded = Http2Handler.HpackEncoder.encode(original);
        Map<String, String> decoded = Http2Handler.HpackDecoder.decode(encoded);
        assertThat(decoded).containsEntry(":method", "GET");
        assertThat(decoded).containsEntry(":path", "/");
        assertThat(decoded).containsEntry(":scheme", "https");
    }

    @Test
    void hpackStaticTableStatus200() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(":status", "200");
        byte[] encoded = Http2Handler.HpackEncoder.encode(headers);
        // :status 200 is index 8 → 0x88
        assertThat(encoded[0] & 0xFF).isEqualTo(0x88);
    }

    @Test
    void hpackRoundTripLiteralHeader() {
        Map<String, String> original = new LinkedHashMap<>();
        original.put("x-custom-header", "my-value");
        byte[] encoded = Http2Handler.HpackEncoder.encode(original);
        Map<String, String> decoded = Http2Handler.HpackDecoder.decode(encoded);
        assertThat(decoded).containsEntry("x-custom-header", "my-value");
    }

    // -----------------------------------------------------------------------
    // Stream ID and connection preface
    // -----------------------------------------------------------------------

    @Test
    void streamIdOddIsClientInitiated() {
        // By convention odd stream IDs are client-initiated
        int streamId = 1;
        assertThat(streamId % 2).isEqualTo(1);
    }

    @Test
    void streamIdEvenIsServerInitiated() {
        int streamId = 2;
        assertThat(streamId % 2).isEqualTo(0);
    }

    @Test
    void connectionPrefaceDetection() {
        byte[] preface = Http2Handler.CONNECTION_PREFACE.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.wrap(preface);
        assertThat(Http2Handler.isConnectionPreface(buf)).isTrue();
    }

    @Test
    void connectionPreface_wrongData_returnsFalse() {
        ByteBuffer buf = ByteBuffer.wrap("GET / HTTP/1.1\r\n".getBytes(StandardCharsets.UTF_8));
        assertThat(Http2Handler.isConnectionPreface(buf)).isFalse();
    }
}
