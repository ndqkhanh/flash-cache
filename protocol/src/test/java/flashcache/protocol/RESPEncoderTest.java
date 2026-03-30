package flashcache.protocol;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class RESPEncoderTest {

    private static String str(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    // -----------------------------------------------------------------------
    // Simple String
    // -----------------------------------------------------------------------

    @Test
    void encodeSimpleString_ok() {
        assertThat(str(RESPEncoder.encodeSimpleString("OK"))).isEqualTo("+OK\r\n");
    }

    @Test
    void encodeSimpleString_pong() {
        assertThat(str(RESPEncoder.encodeSimpleString("PONG"))).isEqualTo("+PONG\r\n");
    }

    // -----------------------------------------------------------------------
    // Error
    // -----------------------------------------------------------------------

    @Test
    void encodeError() {
        assertThat(str(RESPEncoder.encodeError("ERR unknown command"))).isEqualTo("-ERR unknown command\r\n");
    }

    // -----------------------------------------------------------------------
    // Integer
    // -----------------------------------------------------------------------

    @Test
    void encodeInteger_positive() {
        assertThat(str(RESPEncoder.encodeInteger(42))).isEqualTo(":42\r\n");
    }

    @Test
    void encodeInteger_negative() {
        assertThat(str(RESPEncoder.encodeInteger(-1))).isEqualTo(":-1\r\n");
    }

    @Test
    void encodeInteger_zero() {
        assertThat(str(RESPEncoder.encodeInteger(0))).isEqualTo(":0\r\n");
    }

    // -----------------------------------------------------------------------
    // Bulk String
    // -----------------------------------------------------------------------

    @Test
    void encodeBulkString_hello() {
        assertThat(str(RESPEncoder.encodeBulkString("hello"))).isEqualTo("$5\r\nhello\r\n");
    }

    @Test
    void encodeBulkString_null() {
        assertThat(str(RESPEncoder.encodeBulkString(null))).isEqualTo("$-1\r\n");
    }

    @Test
    void encodeNullBulkString() {
        assertThat(str(RESPEncoder.encodeNullBulkString())).isEqualTo("$-1\r\n");
    }

    @Test
    void encodeBulkString_empty() {
        assertThat(str(RESPEncoder.encodeBulkString(""))).isEqualTo("$0\r\n\r\n");
    }

    // -----------------------------------------------------------------------
    // Array
    // -----------------------------------------------------------------------

    @Test
    void encodeArray_empty() {
        assertThat(str(RESPEncoder.encodeArray(List.of()))).isEqualTo("*0\r\n");
    }

    @Test
    void encodeArray_bulkStrings() {
        List<RESPMessage> elements = List.of(
                new RESPMessage.BulkString("SET"),
                new RESPMessage.BulkString("key"),
                new RESPMessage.BulkString("value")
        );
        String encoded = str(RESPEncoder.encodeArray(elements));
        assertThat(encoded).isEqualTo("*3\r\n$3\r\nSET\r\n$3\r\nkey\r\n$5\r\nvalue\r\n");
    }

    @Test
    void encodeArray_nested() {
        List<RESPMessage> inner = List.of(new RESPMessage.SimpleString("inner"));
        List<RESPMessage> outer = List.of(
                new RESPMessage.ArrayMessage(inner),
                new RESPMessage.IntegerMessage(7)
        );
        String encoded = str(RESPEncoder.encodeArray(outer));
        assertThat(encoded).isEqualTo("*2\r\n*1\r\n+inner\r\n:7\r\n");
    }

    // -----------------------------------------------------------------------
    // Round-trip: encode -> decode -> equals original
    // -----------------------------------------------------------------------

    @Test
    void roundTrip_simpleString() {
        RESPMessage original = new RESPMessage.SimpleString("HELLO");
        byte[] encoded = RESPEncoder.encodeMessage(original);
        RESPMessage decoded = RESPDecoder.decode(ByteBuffer.wrap(encoded));
        assertThat(decoded).isEqualTo(original);
    }

    @Test
    void roundTrip_bulkString() {
        RESPMessage original = new RESPMessage.BulkString("world");
        byte[] encoded = RESPEncoder.encodeMessage(original);
        RESPMessage decoded = RESPDecoder.decode(ByteBuffer.wrap(encoded));
        assertThat(decoded).isEqualTo(original);
    }

    @Test
    void roundTrip_array() {
        RESPMessage original = new RESPMessage.ArrayMessage(List.of(
                new RESPMessage.BulkString("GET"),
                new RESPMessage.BulkString("mykey")
        ));
        byte[] encoded = RESPEncoder.encodeMessage(original);
        RESPMessage decoded = RESPDecoder.decode(ByteBuffer.wrap(encoded));
        assertThat(decoded).isEqualTo(original);
    }

    @Test
    void roundTrip_integer() {
        RESPMessage original = new RESPMessage.IntegerMessage(12345L);
        byte[] encoded = RESPEncoder.encodeMessage(original);
        RESPMessage decoded = RESPDecoder.decode(ByteBuffer.wrap(encoded));
        assertThat(decoded).isEqualTo(original);
    }

    @Test
    void roundTrip_error() {
        RESPMessage original = new RESPMessage.ErrorMessage("ERR something went wrong");
        byte[] encoded = RESPEncoder.encodeMessage(original);
        RESPMessage decoded = RESPDecoder.decode(ByteBuffer.wrap(encoded));
        assertThat(decoded).isEqualTo(original);
    }
}
