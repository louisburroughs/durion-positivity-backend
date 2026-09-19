package com.positivity.mcp.internal.exception;

/**
 * A message id that does not exist within the addressed conversation for the bound tenant/owner
 * (#2073, ADR-0062). Same 404-not-403 rule as {@link ConversationNotFoundException}: another
 * subject's message id and one that never existed answer identically.
 */
public class MessageNotFoundException extends RuntimeException {
    public MessageNotFoundException(String message) {
        super(message);
    }
}
