package flashcache.protocol;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class RESPDecoderTest {

    private static ByteBuffer buf(String s) {
        return ByteBuffer.wrap(s.getBytes(StandardCharsets.UTF_8));
    }

    // -----------------------------------------------------------------------
    // Simple String
    // -----------------------------------------------------------------------

    @Test
    void decodeSimpleString_ok() {
        RESPMessage msg = RESPDecoder.decode(buf("+OK\r\n"));
        assertThat(msg).isInstanceOf(RESPMessage.SimpleString.class);
        assertThat(((RESPMessage.SimpleString) msg).value()).isEqualTo("OK");
    }

    @Test
    void decodeSimpleString_empty() {
        RESPMessage msg = RESPDecoder.decode(buf("+\r\n"));
        assertThat(((RESPMessage.SimpleString) msg).value()).isEqualTo("");
    }

    // -----------------------------------------------------------------------
    // Error
    // -----------------------------------------------------------------------

    @Test
    void decodeError_unknown_command() {
        RESPMessage msg = RESPDecoder.decode(buf("-ERR unknown command\r\n"));
        assertThat(msg).isInstanceOf(RESPMessage.ErrorMessage.class);
        assertThat(((RESPMessage.ErrorMessage) msg).message()).isEqualTo("ERR unknown command");
    }

    // -----------------------------------------------------------------------
    // Integer
    // -----------------------------------------------------------------------

    @Test
    void decodeInteger_positive() {
        RESPMessage msg = RESPDecoder.decode(buf(":42\r\n"));
        assertThat(msg).isInstanceOf(RESPMessage.IntegerMessage.class);
        assertThat(((RESPMessage.IntegerMessage) msg).value()).isEqualTo(42L);
    }

    @Test
    void decodeInteger_negative() {
        RESPMessage msg = RESPDecoder.decode(buf(":-1\r\n"));
        assertThat(((RESPMessage.IntegerMessage) msg).value()).isEqualTo(-1L);
    }

    @Test
    void decodeInteger_large() {
        RESPMessage msg = RESPDecoder.decode(buf(":1000\r\n"));
        assertThat(((RESPMessage.IntegerMessage) msg).value()).isEqualTo(1000L);
    }

    // -----------------------------------------------------------------------
    // Bulk String
    // -----------------------------------------------------------------------

    @Test
    void decodeBulkString_hello() {
        RESPMessage msg = RESPDecoder.decode(buf("$5\r\nhello\r\n"));
        assertThat(msg).isInstanceOf(RESPMessage.BulkString.class);
        assertThat(((RESPMessage.BulkString) msg).value()).isEqualTo("hello");
    }

    @Test
    void decodeBulkString_null() {
        RESPMessage msg = RESPDecoder.decode(buf("$-1\r\n"));
        assertThat(msg).isInstanceOf(RESPMessage.BulkString.class);
        assertThat(((RESPMessage.BulkString) msg).value()).isNull();
    }

    @Test
    void decodeBulkString_empty() {
        RESPMessage msg = RESPDecoder.decode(buf("$0\r\n\r\n"));
        assertThat(msg).isInstanceOf(RESPMessage.BulkString.class);
        assertThat(((RESPMessage.BulkString) msg).value()).isEqualTo("");
    }

    // -----------------------------------------------------------------------
    // Array
    // -----------------------------------------------------------------------

    @Test
    void decodeArray_empty() {
        RESPMessage msg = RESPDecoder.decode(buf("*0\r\n"));
        assertThat(msg).isInstanceOf(RESPMessage.ArrayMessage.class);
        assertThat(((RESPMessage.ArrayMessage) msg).elements()).isEmpty();
    }

    @Test
    void decodeArray_setCommand() {
        // *3\r\n$3\r\nSET\r\n$3\r\nkey\r\n$5\r\nvalue\r\n
        String input = "*3\r\n$3\r\nSET\r\n$3\r\nkey\r\n$5\r\nvalue\r\n";
        RESPMessage msg = RESPDecoder.decode(buf(input));
        assertThat(msg).isInstanceOf(RESPMessage.ArrayMessage.class);
        List<RESPMessage> elems = ((RESPMessage.ArrayMessage) msg).elements();
        assertThat(elems).hasSize(3);
        assertThat(((RESPMessage.BulkString) elems.get(0)).value()).isEqualTo("SET");
        assertThat(((RESPMessage.BulkString) elems.get(1)).value()).isEqualTo("key");
        assertThat(((RESPMessage.BulkString) elems.get(2)).value()).isEqualTo("value");
    }

    @Test
    void decodeArray_getCommand() {
        String input = "*2\r\n$3\r\nGET\r\n$3\r\nkey\r\n";
        RESPMessage msg = RESPDecoder.decode(buf(input));
        List<RESPMessage> elems = ((RESPMessage.ArrayMessage) msg).elements();
        assertThat(elems).hasSize(2);
        assertThat(((RESPMessage.BulkString) elems.get(0)).value()).isEqualTo("GET");
        assertThat(((RESPMessage.BulkString) elems.get(1)).value()).isEqualTo("key");
    }

    @Test
    void decodeArray_mixedTypes() {
        // array with a simple string and an integer
        String input = "*2\r\n+hello\r\n:99\r\n";
        RESPMessage msg = RESPDecoder.decode(buf(input));
        List<RESPMessage> elems = ((RESPMessage.ArrayMessage) msg).elements();
        assertThat(elems).hasSize(2);
        assertThat(elems.get(0)).isInstanceOf(RESPMessage.SimpleString.class);
        assertThat(elems.get(1)).isInstanceOf(RESPMessage.IntegerMessage.class);
        assertThat(((RESPMessage.IntegerMessage) elems.get(1)).value()).isEqualTo(99L);
    }

    @Test
    void decodeArray_nested() {
        // *2\r\n*1\r\n+inner\r\n:7\r\n
        String input = "*2\r\n*1\r\n+inner\r\n:7\r\n";
        RESPMessage msg = RESPDecoder.decode(buf(input));
        List<RESPMessage> outer = ((RESPMessage.ArrayMessage) msg).elements();
        assertThat(outer).hasSize(2);
        assertThat(outer.get(0)).isInstanceOf(RESPMessage.ArrayMessage.class);
        List<RESPMessage> inner = ((RESPMessage.ArrayMessage) outer.get(0)).elements();
        assertThat(inner).hasSize(1);
        assertThat(((RESPMessage.SimpleString) inner.get(0)).value()).isEqualTo("inner");
        assertThat(((RESPMessage.IntegerMessage) outer.get(1)).value()).isEqualTo(7L);
    }

    // -----------------------------------------------------------------------
    // Invalid input
    // -----------------------------------------------------------------------

    @Test
    void decodeInvalidType_throwsException() {
        assertThatThrownBy(() -> RESPDecoder.decode(buf("?invalid\r\n")))
                .isInstanceOf(RESPDecoder.RESPDecodeException.class);
    }

    @Test
    void decodeEmpty_throwsException() {
        assertThatThrownBy(() -> RESPDecoder.decode(buf("")))
                .isInstanceOf(RESPDecoder.RESPDecodeException.class);
    }
}
