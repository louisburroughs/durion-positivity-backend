package com.positivity.mcp.internal.exception;

/**
 * A chat turn is already running on the persisted conversation (#2073). Turns on one conversation
 * are serialized: a second concurrent {@code POST /mcp/chat} naming the same conversation is
 * rejected (409 {@code CONVERSATION_BUSY}) rather than run against the same model memory and
 * persisted in completion order. The caller retries once the first turn has answered.
 */
public class ConversationBusyException extends RuntimeException {
    public ConversationBusyException(String message) {
        super(message);
    }
}
