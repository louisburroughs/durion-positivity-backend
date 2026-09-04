package com.positivity.mcp.internal.orchestration.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClientResponseException;

/**
 * Shared support for facade tools that assemble one answer from several downstream REST calls.
 *
 * <p>Legs execute sequentially in declaration order when {@link #render()} is invoked. Each leg
 * supplier is expected to perform its call through the facade's existing instrumented
 * {@link org.springframework.web.client.RestClient} (which already relays the caller's bearer
 * token), so a leg is subject to exactly the same authorization as a standalone tool call.
 *
 * <p>Per-leg outcomes render into a {@code sections} object:
 *
 * <ul>
 *   <li>success &rarr; {@code {"status":"ok","data":<body>}} — the body embeds verbatim when it is
 *       valid JSON, otherwise as a JSON string
 *   <li>HTTP 401/403 &rarr; {@code {"status":"not_authorized"}} — never retried, and the error
 *       response body is deliberately dropped so a denied leg cannot leak data
 *   <li>{@link LegFailure} &rarr; {@code {"status":"error","reason":"<its message>"}} — thrown by
 *       a facade when a leg cannot (or must not) be called, with a message written for the LLM
 *   <li>any other failure &rarr; {@code {"status":"error","reason":"<short reason>"}} with no
 *       stack trace and no response body
 * </ul>
 *
 * <p>The envelope is {@code {"composition":..,"status":..,"sections":{..},"sources":[..]}} where
 * {@code sources} lists the legs that succeeded and {@code status} is {@code "degraded"} instead
 * of {@code "ok"} when a leg marked {@link #require(String)} did not succeed. A failed optional
 * leg degrades only its own section — the tool still returns an answer.
 */
final class ToolComposition {

    private static final Logger LOGGER = LoggerFactory.getLogger(ToolComposition.class);
    private static final ObjectMapper MAPPER =
            new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private final String name;
    private final Map<String, Leg> legs = new LinkedHashMap<>();

    private ToolComposition(@NonNull String name) {
        this.name = name;
    }

    static @NonNull ToolComposition named(@NonNull String name) {
        return new ToolComposition(name);
    }

    @NonNull
    ToolComposition call(@NonNull String legName, @NonNull Supplier<String> downstreamCall) {
        if (legs.containsKey(legName)) {
            // legName is always a hardcoded literal at the facade tool's own call site (#1694: no
            // caller passes a client- or LLM-supplied leg name), so a collision here is a coding
            // defect in this module, not client input -- left as a bare IllegalArgumentException.
            throw new IllegalArgumentException("Duplicate composition leg: " + legName);
        }
        legs.put(legName, new Leg(downstreamCall));
        return this;
    }

    @NonNull
    ToolComposition require(@NonNull String legName) {
        Leg leg = legs.get(legName);
        if (leg == null) {
            // Same as above: legName is a hardcoded literal, so an undeclared leg is a coding
            // defect (a facade referring to a leg it never declared), not client input.
            throw new IllegalArgumentException("Cannot require undeclared composition leg: " + legName);
        }
        leg.required = true;
        return this;
    }

    @NonNull
    String render() {
        ObjectNode sections = MAPPER.createObjectNode();
        ArrayNode sources = MAPPER.createArrayNode();
        boolean degraded = false;
        for (Map.Entry<String, Leg> entry : legs.entrySet()) {
            String legName = entry.getKey();
            Leg leg = entry.getValue();
            ObjectNode section = sections.putObject(legName);
            boolean succeeded = executeLeg(legName, leg, section);
            if (succeeded) {
                sources.add(legName);
            } else if (leg.required) {
                degraded = true;
            }
        }
        ObjectNode envelope = MAPPER.createObjectNode();
        envelope.put("composition", name);
        envelope.put("status", degraded ? "degraded" : "ok");
        envelope.set("sections", sections);
        envelope.set("sources", sources);
        try {
            return MAPPER.writeValueAsString(envelope);
        } catch (JsonProcessingException exception) {
            throw new UncheckedIOException("Failed to render composition envelope: " + name, exception);
        }
    }

    private boolean executeLeg(@NonNull String legName, @NonNull Leg leg, @NonNull ObjectNode section) {
        try {
            String body = leg.downstreamCall.get();
            section.put("status", "ok");
            section.set("data", asJson(body));
            return true;
        } catch (RestClientResponseException exception) {
            int status = exception.getStatusCode().value();
            if (status == 401 || status == 403) {
                // Denied leg: no retry, no escalation, and the response body is never rendered.
                section.put("status", "not_authorized");
            } else {
                section.put("status", "error");
                section.put("reason", "HTTP " + status);
            }
            LOGGER.warn("Composition {} leg {} failed with HTTP {}", name, legName, status);
            return false;
        } catch (LegFailure failure) {
            // Deliberate, facade-authored failure: the message is written for the caller.
            section.put("status", "error");
            section.put("reason", failure.getMessage());
            LOGGER.warn("Composition {} leg {} failed: {}", name, legName, failure.getMessage());
            return false;
        } catch (RuntimeException exception) {
            section.put("status", "error");
            section.put("reason", exception.getClass().getSimpleName());
            LOGGER.warn(
                    "Composition {} leg {} failed error={}",
                    name,
                    legName,
                    exception.getClass().getSimpleName());
            return false;
        }
    }

    private static @NonNull JsonNode asJson(String body) {
        if (body == null || body.isBlank()) {
            return NullNode.getInstance();
        }
        try {
            return MAPPER.readTree(body);
        } catch (JsonProcessingException notJson) {
            return TextNode.valueOf(body);
        }
    }

    private static final class Leg {

        private final Supplier<String> downstreamCall;
        private boolean required;

        private Leg(Supplier<String> downstreamCall) {
            this.downstreamCall = downstreamCall;
        }
    }

    /**
     * Thrown by a leg supplier when the leg cannot (or must not) be executed — e.g. a required
     * input extracted from an earlier leg is unusable. Unlike a generic {@link RuntimeException},
     * whose class name alone is rendered, the message of a {@code LegFailure} IS the section's
     * {@code reason}: write it for the LLM, and never include downstream response bodies.
     */
    static final class LegFailure extends RuntimeException {

        LegFailure(@NonNull String message) {
            super(message);
        }
    }
}
