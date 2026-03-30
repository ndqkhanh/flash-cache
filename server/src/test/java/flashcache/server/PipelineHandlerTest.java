package flashcache.server;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PipelineHandlerTest {

    private PipelineHandler handler;

    // Simple echo processor: returns "+<args joined by space>\r\n"
    private final PipelineHandler.CommandProcessor echoProcessor = args ->
            ("+" + String.join(" ", args) + "\r\n").getBytes(StandardCharsets.UTF_8);

    @BeforeEach
    void setUp() {
        handler = new PipelineHandler();
    }

    // Helper: encode a single RESP array command
    private ByteBuffer respArray(String... args) {
        StringBuilder sb = new StringBuilder();
        sb.append('*').append(args.length).append("\r\n");
        for (String arg : args) {
            sb.append('$').append(arg.length()).append("\r\n");
            sb.append(arg).append("\r\n");
        }
        return ByteBuffer.wrap(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    // Helper: concatenate multiple ByteBuffers
    private ByteBuffer concat(ByteBuffer... bufs) {
        int total = 0;
        for (ByteBuffer b : bufs) total += b.remaining();
        ByteBuffer out = ByteBuffer.allocate(total);
        for (ByteBuffer b : bufs) out.put(b);
        out.flip();
        return out;
    }

    // Helper: read result as UTF-8 string
    private String asString(ByteBuffer buf) {
        byte[] bytes = new byte[buf.remaining()];
        buf.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    @Test
    void singleCommand_processedCorrectly() {
        ByteBuffer input = respArray("GET", "mykey");
        ByteBuffer output = handler.processPipeline(input, echoProcessor);
        assertThat(asString(output)).isEqualTo("+GET mykey\r\n");
    }

    @Test
    void twoCommandsPipelined_bothProcessed() {
        ByteBuffer input = concat(respArray("SET", "k", "v"), respArray("GET", "k"));
        ByteBuffer output = handler.processPipeline(input, echoProcessor);
        assertThat(asString(output)).isEqualTo("+SET k v\r\n+GET k\r\n");
    }

    @Test
    void threeCommandsPipelined_inOrder() {
        ByteBuffer input = concat(
                respArray("CMD1", "a"),
                respArray("CMD2", "b"),
                respArray("CMD3", "c")
        );
        ByteBuffer output = handler.processPipeline(input, echoProcessor);
        assertThat(asString(output)).isEqualTo("+CMD1 a\r\n+CMD2 b\r\n+CMD3 c\r\n");
    }

    @Test
    void partialCommand_bufferedForNextCall() {
        // Send first full command + start of second
        String full = "*2\r\n$3\r\nGET\r\n$5\r\nmykey\r\n";
        String partial = "*2\r\n$3\r\nSET\r\n"; // incomplete — no value yet

        ByteBuffer input1 = ByteBuffer.wrap((full + partial).getBytes(StandardCharsets.UTF_8));
        ByteBuffer output1 = handler.processPipeline(input1, echoProcessor);

        // First call: only first command completed
        assertThat(asString(output1)).isEqualTo("+GET mykey\r\n");
        assertThat(handler.pipelinedCount()).isEqualTo(1);

        // Now send the rest of the second command
        String rest = "$5\r\nvalue\r\n";
        ByteBuffer input2 = ByteBuffer.wrap(rest.getBytes(StandardCharsets.UTF_8));
        ByteBuffer output2 = handler.processPipeline(input2, echoProcessor);

        assertThat(asString(output2)).isEqualTo("+SET value\r\n");
        assertThat(handler.pipelinedCount()).isEqualTo(2);
    }

    @Test
    void emptyInput_returnsEmptyOutput() {
        ByteBuffer input = ByteBuffer.allocate(0);
        ByteBuffer output = handler.processPipeline(input, echoProcessor);
        assertThat(output.remaining()).isEqualTo(0);
    }

    @Test
    void pipelinedCount_tracksTotal() {
        handler.processPipeline(concat(respArray("A"), respArray("B")), echoProcessor);
        handler.processPipeline(respArray("C"), echoProcessor);
        assertThat(handler.pipelinedCount()).isEqualTo(3);
    }

    @Test
    void largePipeline_tenCommands_allProcessed() {
        ByteBuffer[] bufs = new ByteBuffer[10];
        StringBuilder expectedSb = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            bufs[i] = respArray("CMD" + i, "arg" + i);
            expectedSb.append("+CMD").append(i).append(" arg").append(i).append("\r\n");
        }
        ByteBuffer input = concat(bufs);
        ByteBuffer output = handler.processPipeline(input, echoProcessor);
        assertThat(asString(output)).isEqualTo(expectedSb.toString());
        assertThat(handler.pipelinedCount()).isEqualTo(10);
    }

    @Test
    void mixedCommandSizes_handledCorrectly() {
        // Commands with varying arg lengths
        ByteBuffer input = concat(
                respArray("PING"),
                respArray("SET", "a-very-long-key-name", "a-very-long-value-that-should-still-work"),
                respArray("GET", "x")
        );
        ByteBuffer output = handler.processPipeline(input, echoProcessor);
        String result = asString(output);
        assertThat(result).contains("+PING\r\n");
        assertThat(result).contains("+SET a-very-long-key-name a-very-long-value-that-should-still-work\r\n");
        assertThat(result).contains("+GET x\r\n");
        assertThat(handler.pipelinedCount()).isEqualTo(3);
    }
}
