package com.positivity.mcp.internal.exception;

/**
 * A conversation id that does not exist for the bound tenant/owner (#2073, ADR-0062). Raised for
 * another subject's conversation id exactly as for an id that never existed: the two are
 * indistinguishable on purpose, so the response reveals nothing about another subject's
 * conversations (404, never 403). Also raised when {@code POST /mcp/chat} is given a
 * caller-supplied UUID {@code conversationId} that is not found/owned — the server never creates
 * a new conversation under a caller-chosen id.
 */
public class ConversationNotFoundException extends RuntimeException {
    public ConversationNotFoundException(String message) {
        super(message);
    }
}
