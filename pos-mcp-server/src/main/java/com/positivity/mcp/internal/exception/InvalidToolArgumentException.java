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
 * <p>Note what currently happens to this exception in production, because it is not what the name
 * suggests: nothing maps it to an HTTP status. Every facade tool is invoked through {@code
 * SpringAiToolCallbackResolver.ReflectiveToolCallback#call}, which catches the reflective
 * {@code InvocationTargetException} and rethrows a generic {@code IllegalStateException}, discarding
 * this type; the chat turn that wraps it then swallows any {@code RuntimeException} and degrades to
 * the answer-resolution ladder with a 200. So a malformed tool argument reaches neither an
 * {@code @ExceptionHandler} nor the model as a self-correcting tool result -- it did not before this
 * type existed either, so re-typing changed no behaviour. The type earns its place by keeping these
 * throws out of the way of any future blanket {@code IllegalArgumentException} handler and by naming
 * the intent; making it load-bearing means raising Spring AI's {@code ToolExecutionException} so the
 * framework's tool-error processor can hand the model something to correct, which is a separate
 * change from issue #1694.
 */
public class InvalidToolArgumentException extends RuntimeException {
    public InvalidToolArgumentException(String message) {
        super(message);
    }

    public InvalidToolArgumentException(String message, Throwable cause) {
        super(message, cause);
    }
}
