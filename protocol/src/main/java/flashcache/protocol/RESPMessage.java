package flashcache.protocol;

import java.util.List;

public sealed interface RESPMessage {
    record SimpleString(String value) implements RESPMessage {}
    record ErrorMessage(String message) implements RESPMessage {}
    record IntegerMessage(long value) implements RESPMessage {}
    record BulkString(String value) implements RESPMessage {} // value=null for null bulk string
    record ArrayMessage(List<RESPMessage> elements) implements RESPMessage {}
}
