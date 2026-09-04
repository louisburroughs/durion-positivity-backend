package com.positivity.mcp.internal.exception;

/**
 * An argument supplied to an MCP tool call is malformed or fails the tool's own input validation
 * (a bad date, an out-of-range count, an unsupported enum literal, unparseable JSON, etc). The
 * argument value ultimately originates from the calling LLM's tool-call payload -- itself driven
 * by end-user chat input -- so this is genuine client input, not a server-side defect, even though
 * it is validated deep inside tool-dispatch code rather than at a controller boundary. Deliberately
 * not an {@code IllegalArgumentException} subtype: that type is also thrown by the JDK and
 * third-party libraries (Hibernate, {@code UUID.fromString}, etc) for reasons that have nothing to
 * do with client input, and a blanket {@code IllegalArgumentException} handler cannot tell the two
 * apart (issue #1694).
 *
 * <p>This type is load-bearing as of #1711. {@code SpringAiToolCallbackResolver.ReflectiveToolCallback#call}
 * wraps whatever a tool throws in Spring AI's {@code ToolExecutionException}, which is the only type
 * {@code DefaultToolCallingManager} catches and converts into a tool result the model can read and
 * retry from. So a malformed argument now comes back to the model as a correctable error naming what
 * was wrong, instead of escaping the tool-calling loop and killing the turn.
 *
 * <p>Nothing maps this type to an HTTP status, and nothing should: it is raised inside tool dispatch,
 * not at a controller boundary, and its audience is the calling model rather than an HTTP client.
 * Keeping it distinct from {@code IllegalArgumentException} is what lets a future blanket handler for
 * that type stay away from these throws (#1694).
 */
public class InvalidToolArgumentException extends RuntimeException {
    public InvalidToolArgumentException(String message) {
        super(message);
    }

    public InvalidToolArgumentException(String message, Throwable cause) {
        super(message, cause);
    }
}
