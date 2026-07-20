package com.positivity.accounting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.accounting.internal.service.PredicateParser;
import com.positivity.accounting.internal.service.PredicateParser.ParseException;
import com.positivity.accounting.internal.service.PredicateParser.Predicate;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests for the E2 condition-predicate whitelist grammar
 * (issue #946): parsing, strictness, and safe-default evaluation
 * semantics of {@link PredicateParser}.
 */
@DisplayName("PredicateParser (E2 whitelist predicate grammar)")
class PredicateParserTest {

    private static final String EVENT_TYPE = "billing.invoicePosted";

    private static boolean eval(String predicate, Map<String, Object> payload) {
        return PredicateParser.parse(predicate).matches(EVENT_TYPE, payload);
    }

    private static Map<String, Object> payload(String key, Object value) {
        Map<String, Object> payload = new HashMap<>();
        payload.put(key, value);
        return payload;
    }

    // ── Backward compatibility ───────────────────────────────────────────

    @Nested
    @DisplayName("pre-E2 eventType equality predicates")
    class EventTypeEquality {

        @Test
        @DisplayName("existing eventType == '<value>' predicates parse and match unchanged")
        void legacyEventTypeEqualityStillWorks() {
            assertThat(eval("eventType == 'billing.invoicePosted'", Map.of())).isTrue();
            assertThat(eval("eventType == 'other.event'", Map.of())).isFalse();
        }

        @Test
        @DisplayName("eventType != excludes the named type")
        void eventTypeInequality() {
            assertThat(eval("eventType != 'other.event'", Map.of())).isTrue();
            assertThat(eval("eventType != 'billing.invoicePosted'", Map.of())).isFalse();
        }

        @Test
        @DisplayName("null eventType is a safe non-match, even for !=")
        void nullEventTypeNeverMatches() {
            Predicate predicate = PredicateParser.parse("eventType == 'x'");
            assertThat(predicate.matches(null, Map.of())).isFalse();
            assertThat(PredicateParser.parse("eventType != 'x'").matches(null, Map.of()))
                    .isFalse();
        }
    }

    // ── Operators ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("comparison operators")
    class Operators {

        @Test
        @DisplayName("all six operators evaluate correctly against a numeric payload value")
        void numericOperators() {
            Map<String, Object> payload = payload("amount", new BigDecimal("100.00"));

            assertThat(eval("payload.amount == 100.00", payload)).isTrue();
            assertThat(eval("payload.amount != 100.00", payload)).isFalse();
            assertThat(eval("payload.amount >= 100.00", payload)).isTrue();
            assertThat(eval("payload.amount <= 100.00", payload)).isTrue();
            assertThat(eval("payload.amount > 100.00", payload)).isFalse();
            assertThat(eval("payload.amount < 100.00", payload)).isFalse();

            assertThat(eval("payload.amount > 99.99", payload)).isTrue();
            assertThat(eval("payload.amount < 100.01", payload)).isTrue();
            assertThat(eval("payload.amount != 99", payload)).isTrue();
        }

        @Test
        @DisplayName("string equality and inequality compare the exact string")
        void stringOperators() {
            Map<String, Object> payload = payload("paymentMethod", "CASH");

            assertThat(eval("payload.paymentMethod == 'CASH'", payload)).isTrue();
            assertThat(eval("payload.paymentMethod == 'CARD'", payload)).isFalse();
            assertThat(eval("payload.paymentMethod != 'CARD'", payload)).isTrue();
            assertThat(eval("payload.paymentMethod == 'cash'", payload)).isFalse(); // case-sensitive
        }

        @Test
        @DisplayName("numeric equality is scale-insensitive (BigDecimal compareTo)")
        void numericEqualityIgnoresScale() {
            assertThat(eval("payload.amount == 100", payload("amount", new BigDecimal("100.00"))))
                    .isTrue();
            assertThat(eval("payload.amount == 100.0", payload("amount", 100))).isTrue();
        }

        @Test
        @DisplayName("signed and decimal literals parse and compare")
        void signedAndDecimalLiterals() {
            assertThat(eval("payload.delta > -0.5", payload("delta", new BigDecimal("-0.25"))))
                    .isTrue();
            assertThat(eval("payload.delta < -0.5", payload("delta", new BigDecimal("-0.25"))))
                    .isFalse();
            assertThat(eval("payload.delta == +3.5", payload("delta", new BigDecimal("3.50"))))
                    .isTrue();
        }
    }

    // ── Coercion and safe defaults ───────────────────────────────────────

    @Nested
    @DisplayName("coercion and safe-default semantics")
    class SafeDefaults {

        @Test
        @DisplayName("numeric string payload values coerce for numeric comparison")
        void numericStringCoercion() {
            assertThat(eval("payload.amount >= 100.00", payload("amount", "150.25")))
                    .isTrue();
            assertThat(eval("payload.amount >= 100.00", payload("amount", "99.99")))
                    .isFalse();
            assertThat(eval("payload.amount == 100", payload("amount", "100.00")))
                    .isTrue();
        }

        @Test
        @DisplayName("payload Number types (Integer, Long, Double, BigDecimal) all coerce")
        void numberTypesCoerce() {
            assertThat(eval("payload.amount > 5", payload("amount", 10))).isTrue();
            assertThat(eval("payload.amount > 5", payload("amount", 10L))).isTrue();
            assertThat(eval("payload.amount > 5", payload("amount", 10.5d))).isTrue();
            assertThat(eval("payload.amount > 5", payload("amount", new BigDecimal("10"))))
                    .isTrue();
        }

        @Test
        @DisplayName("non-coercible value under a numeric comparison is a non-match, even for !=")
        void nonCoercibleValueIsNonMatch() {
            assertThat(eval("payload.amount > 5", payload("amount", "not-a-number")))
                    .isFalse();
            assertThat(eval("payload.amount != 5", payload("amount", "not-a-number")))
                    .isFalse();
            assertThat(eval("payload.amount == 5", payload("amount", Boolean.TRUE)))
                    .isFalse();
        }

        @Test
        @DisplayName("non-string value under a string comparison is a non-match, even for !=")
        void nonStringValueIsNonMatch() {
            assertThat(eval("payload.method == 'CASH'", payload("method", 42))).isFalse();
            assertThat(eval("payload.method != 'CASH'", payload("method", 42))).isFalse();
        }

        @Test
        @DisplayName("missing path is a non-match, not an error — even for !=")
        void missingPathIsNonMatch() {
            assertThat(eval("payload.missing == 'x'", payload("other", "y"))).isFalse();
            assertThat(eval("payload.missing != 'x'", payload("other", "y"))).isFalse();
            assertThat(eval("payload.missing > 5", payload("other", "y"))).isFalse();
        }

        @Test
        @DisplayName("null payload map is a non-match, not an error")
        void nullPayloadIsNonMatch() {
            assertThat(PredicateParser.parse("payload.amount > 5").matches(EVENT_TYPE, null))
                    .isFalse();
        }

        @Test
        @DisplayName("nested paths resolve through nested maps")
        void nestedPathResolves() {
            Map<String, Object> payload = payload("totals", Map.of("tax", new BigDecimal("8.25")));
            assertThat(eval("payload.totals.tax == 8.25", payload)).isTrue();
            assertThat(eval("payload.totals.missing == 8.25", payload)).isFalse();
        }

        @Test
        @DisplayName("non-map value mid-path is a non-match, not an error")
        void nonMapMidPathIsNonMatch() {
            assertThat(eval("payload.totals.tax == 8.25", payload("totals", "flat-string")))
                    .isFalse();
            assertThat(eval("payload.totals.tax != 8.25", payload("totals", 12)))
                    .isFalse();
        }

        @Test
        @DisplayName("non-scalar terminal value (map or list) is a non-match")
        void nonScalarTerminalIsNonMatch() {
            assertThat(eval("payload.totals == 'x'", payload("totals", Map.of("a", 1))))
                    .isFalse();
            assertThat(eval("payload.items > 0", payload("items", List.of(1, 2))))
                    .isFalse();
        }
    }

    // ── Conjunction ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("&& conjunction")
    class Conjunction {

        @Test
        @DisplayName("all clauses must match")
        void allClausesMustMatch() {
            Map<String, Object> payload = new HashMap<>();
            payload.put("amount", "150.00");
            payload.put("paymentMethod", "CASH");

            assertThat(eval("payload.amount >= 100.00 && eventType == 'billing.invoicePosted'", payload))
                    .isTrue();
            assertThat(eval("payload.amount >= 100.00 && payload.paymentMethod == 'CARD'", payload))
                    .isFalse();
            assertThat(eval("payload.amount >= 200.00 && payload.paymentMethod == 'CASH'", payload))
                    .isFalse();
            assertThat(eval("payload.amount >= 100.00 && payload.paymentMethod == 'CASH' && eventType != 'x'", payload))
                    .isTrue();
        }

        @Test
        @DisplayName("a false clause short-circuits — later payload paths are never read")
        void shortCircuits() {
            java.util.Set<Object> accessedKeys = new java.util.HashSet<>();
            Map<String, Object> payload = new HashMap<>() {
                @Override
                public Object get(Object key) {
                    accessedKeys.add(key);
                    return super.get(key);
                }
            };
            payload.put("first", "NOPE");
            payload.put("second", "YES");

            assertThat(eval("payload.first == 'MATCH' && payload.second == 'YES'", payload))
                    .isFalse();
            assertThat(accessedKeys).contains("first").doesNotContain("second");
        }
    }

    // ── Strict parse failures ────────────────────────────────────────────

    @Nested
    @DisplayName("strict parse failures")
    class ParseFailures {

        @ParameterizedTest(name = "[{index}] rejects: {0}")
        @ValueSource(
                strings = {
                    // trailing garbage
                    "eventType == 'x' extra",
                    "payload.amount > 5 5",
                    "eventType == 'x' && payload.a == 1 junk",
                    // unbalanced quotes
                    "eventType == 'x",
                    "payload.method == 'CASH",
                    "eventType == x'",
                    // unknown identifiers / bad lhs
                    "foo == 'x'",
                    "event == 'x'",
                    "payload == 'x'",
                    "payload. == 'x'",
                    "payload..amount == 5",
                    // empty / dangling clauses
                    "",
                    "   ",
                    "&& eventType == 'x'",
                    "eventType == 'x' &&",
                    "eventType == 'x' && && payload.a == 1",
                    // missing pieces
                    "eventType",
                    "eventType ==",
                    "payload.amount >",
                    "== 'x'",
                    // unsupported operators / syntax
                    "eventType = 'x'",
                    "payload.amount ! 5",
                    "eventType == 'x' & payload.a == 1",
                    "eventType == 'x' || payload.a == 1",
                    "payload.amount => 5",
                    "(eventType == 'x')",
                    "payload.amount == 5;",
                    "eventType == \"x\"",
                    // ordering op with string literal
                    "payload.amount > 'high'",
                    "payload.amount >= 'x'",
                    "payload.amount < 'x'",
                    "payload.amount <= 'x'",
                    "eventType > 'x'",
                    // malformed numbers
                    "payload.amount == 5.",
                    "payload.amount == .5",
                    "payload.amount == -",
                    "payload.amount == 'x' 'y'"
                })
        void rejectsMalformedPredicates(String predicate) {
            assertThatThrownBy(() -> PredicateParser.parse(predicate))
                    .isInstanceOf(ParseException.class)
                    .hasMessageContaining("at position");
        }

        @Test
        @DisplayName("ordering operator with string literal names the operator in the error")
        void orderingWithStringLiteralNamesOperator() {
            assertThatThrownBy(() -> PredicateParser.parse("payload.amount >= 'high'"))
                    .isInstanceOf(ParseException.class)
                    .hasMessageContaining(">=")
                    .hasMessageContaining("numeric literal");
        }

        @Test
        @DisplayName("parse exception carries the character position of the offending input")
        void parseExceptionCarriesPosition() {
            assertThatThrownBy(() -> PredicateParser.parse("eventType == 'x' extra"))
                    .isInstanceOfSatisfying(
                            ParseException.class,
                            e -> assertThat(e.getPosition()).isEqualTo(17));
        }
    }

    // ── Lenient whitespace ───────────────────────────────────────────────

    @Test
    @DisplayName("whitespace between tokens is optional and flexible")
    void whitespaceFlexible() {
        Map<String, Object> payload = payload("amount", 150);
        assertThat(eval("payload.amount>=100", payload)).isTrue();
        assertThat(eval("  payload.amount   >=   100  ", payload)).isTrue();
        assertThat(eval("eventType=='billing.invoicePosted'&&payload.amount>100", payload))
                .isTrue();
    }
}
