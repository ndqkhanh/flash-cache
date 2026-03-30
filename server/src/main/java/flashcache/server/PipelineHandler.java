package flashcache.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Handles pipelined RESP requests: multiple RESP array commands arriving in a
 * single TCP read.  Incomplete (partial) commands at the end of the buffer are
 * preserved so they can be retried on the next read.
 *
 * <p>RESP array format (subset we care about for commands):
 * <pre>
 *   *&lt;n&gt;\r\n
 *   $&lt;len&gt;\r\n&lt;bytes&gt;\r\n   (repeated n times)
 * </pre>
 */
public class PipelineHandler {

    private static final Logger log = LoggerFactory.getLogger(PipelineHandler.class);

    @FunctionalInterface
    public interface CommandProcessor {
        /** Process a single parsed command (list of string args) and return the RESP-encoded response bytes. */
        byte[] process(List<String> args);
    }

    /** Leftover bytes from the previous read that did not form a complete command. */
    private ByteBuffer partialBuffer = ByteBuffer.allocate(0);

    private final AtomicLong pipelinedCountTotal = new AtomicLong(0);

    /**
     * Process all complete RESP commands found in {@code input}, invoking
     * {@code processor} for each.  Returns a ByteBuffer containing all
     * concatenated response bytes (flipped, ready to read).  Any incomplete
     * command at the end is saved internally for the next call.
     */
    public ByteBuffer processPipeline(ByteBuffer input, CommandProcessor processor) {
        // Combine leftover bytes from previous call with new input
        ByteBuffer combined = mergeWithPartial(input);

        List<byte[]> responses = new ArrayList<>();
        int commandsProcessed = 0;

        while (combined.hasRemaining()) {
            int savedPosition = combined.position();
            try {
                List<String> args = parseNextCommand(combined);
                if (args == null) {
                    // Not enough data — save remainder as partial
                    combined.position(savedPosition);
                    break;
                }
                byte[] response = processor.process(args);
                responses.add(response);
                commandsProcessed++;
            } catch (PartialCommandException e) {
                // Incomplete command — rewind and save for next read
                combined.position(savedPosition);
                break;
            }
        }

        // Save remaining bytes as partial for next call
        if (combined.hasRemaining()) {
            byte[] remaining = new byte[combined.remaining()];
            combined.get(remaining);
            partialBuffer = ByteBuffer.wrap(remaining);
        } else {
            partialBuffer = ByteBuffer.allocate(0);
        }

        pipelinedCountTotal.addAndGet(commandsProcessed);
        log.debug("Processed {} pipelined commands", commandsProcessed);

        // Concatenate all responses into one ByteBuffer
        int totalSize = 0;
        for (byte[] r : responses) totalSize += r.length;
        ByteBuffer out = ByteBuffer.allocate(totalSize);
        for (byte[] r : responses) out.put(r);
        out.flip();
        return out;
    }

    /**
     * Total number of commands processed via pipelining across all calls.
     */
    public long pipelinedCount() {
        return pipelinedCountTotal.get();
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private ByteBuffer mergeWithPartial(ByteBuffer input) {
        int partialLen = partialBuffer.remaining();
        int inputLen = input.remaining();
        if (partialLen == 0) {
            // No leftover — work directly on a copy so we don't mutate caller's buffer
            byte[] bytes = new byte[inputLen];
            input.get(bytes);
            return ByteBuffer.wrap(bytes);
        }
        byte[] merged = new byte[partialLen + inputLen];
        partialBuffer.get(merged, 0, partialLen);
        input.get(merged, partialLen, inputLen);
        partialBuffer = ByteBuffer.allocate(0);
        return ByteBuffer.wrap(merged);
    }

    /**
     * Try to parse the next RESP array command from {@code buf}.
     * Returns the list of string arguments, or {@code null} if there is not
     * enough data for even the first byte.  Throws {@link PartialCommandException}
     * if a command started but could not be completed.
     */
    private List<String> parseNextCommand(ByteBuffer buf) {
        if (!buf.hasRemaining()) {
            return null;
        }

        // Peek at the first byte without consuming it permanently
        int startPos = buf.position();
        byte firstByte = buf.get(startPos);

        if (firstByte != '*') {
            // Inline command (not standard RESP array) — treat entire line as single-arg command
            String line = readLine(buf); // may throw PartialCommandException
            if (line == null) return null;
            List<String> args = new ArrayList<>();
            for (String part : line.trim().split("\\s+")) {
                if (!part.isEmpty()) args.add(part);
            }
            return args.isEmpty() ? null : args;
        }

        // RESP array: *<count>\r\n
        buf.get(); // consume '*'
        String countStr = readLine(buf); // throws PartialCommandException if incomplete
        int count;
        try {
            count = Integer.parseInt(countStr);
        } catch (NumberFormatException e) {
            throw new PartialCommandException("Invalid array count: " + countStr);
        }

        List<String> args = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            // Each element: $<len>\r\n<bytes>\r\n
            if (!buf.hasRemaining()) throw new PartialCommandException("Incomplete bulk string header");
            byte marker = buf.get();
            if (marker != '$') throw new PartialCommandException("Expected '$', got " + (char) marker);
            String lenStr = readLine(buf);
            int len;
            try {
                len = Integer.parseInt(lenStr);
            } catch (NumberFormatException e) {
                throw new PartialCommandException("Invalid bulk string length: " + lenStr);
            }
            if (buf.remaining() < len + 2) throw new PartialCommandException("Incomplete bulk string data");
            byte[] bytes = new byte[len];
            buf.get(bytes);
            // consume \r\n
            buf.get(); // \r
            buf.get(); // \n
            args.add(new String(bytes, StandardCharsets.UTF_8));
        }
        return args;
    }

    /**
     * Read bytes up to the next \r\n, consuming them including the CRLF.
     * Returns the string content before the CRLF.
     * Throws {@link PartialCommandException} if no CRLF found.
     */
    private String readLine(ByteBuffer buf) {
        int start = buf.position();
        int limit = buf.limit();
        for (int i = start; i < limit - 1; i++) {
            if (buf.get(i) == '\r' && buf.get(i + 1) == '\n') {
                int lineLen = i - start;
                byte[] bytes = new byte[lineLen];
                buf.get(bytes);
                buf.get(); // \r
                buf.get(); // \n
                return new String(bytes, StandardCharsets.UTF_8);
            }
        }
        throw new PartialCommandException("CRLF not found — partial command");
    }

    /** Signals that a command started but could not be completed with available bytes. */
    static class PartialCommandException extends RuntimeException {
        PartialCommandException(String msg) {
            super(msg);
        }
    }
}
