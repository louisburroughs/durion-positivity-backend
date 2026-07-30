package com.positivity.customer.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.customer.internal.domain.SegmentAttribute;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.jspecify.annotations.NonNull;

/**
 * One entry of the segment attribute catalog (#1144).
 *
 * <p>The catalog endpoint is how the attributes are documented as available: a predicate
 * author (marketer UI, another service) discovers what can be referenced, with which
 * operators, without reading this module's source.
 */
@Schema(description = "One whitelisted attribute a segment predicate may reference")
public record SegmentAttributeResponse(
        @Schema(
                description = "Wire name used in predicates",
                example = "service.monthsSinceLast",
                requiredMode = REQUIRED)
        String attribute,

        @Schema(
                description = "Operand shape the attribute compares against",
                example = "NUMBER",
                allowableValues = {"TEXT", "ENUM_TEXT", "NUMBER", "BOOLEAN", "UUID_LIST", "KEYED_TEXT"},
                requiredMode = REQUIRED)
        String operandKind,

        @Schema(
                description = "Operators the attribute accepts",
                example = "[\"GREATER_THAN\"]",
                requiredMode = REQUIRED)
        List<String> operators,

        @Schema(
                description = "Whether the attribute applies only to commercial parties",
                example = "false",
                requiredMode = REQUIRED)
        boolean commercialOnly,

        @Schema(
                description = "Whether the attribute requires a bracketed key, e.g. party.externalIdentifier[QBO]",
                example = "false",
                requiredMode = REQUIRED)
        boolean keyed,

        @Schema(
                description = "What the attribute means",
                example = "Whole months since the most recent completed service",
                requiredMode = REQUIRED)
        String description) {

    public static @NonNull SegmentAttributeResponse from(@NonNull SegmentAttribute attribute) {
        return new SegmentAttributeResponse(
                attribute.wireName(),
                attribute.operandKind().name(),
                attribute.allowedOperators().stream().map(Enum::name).toList(),
                attribute.isCommercialOnly(),
                attribute.operandKind() == SegmentAttribute.OperandKind.KEYED_TEXT,
                attribute.description());
    }
}
