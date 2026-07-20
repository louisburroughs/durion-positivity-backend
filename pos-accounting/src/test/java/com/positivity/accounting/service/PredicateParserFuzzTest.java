package com.positivity.accounting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.positivity.accounting.internal.service.PredicateParser;
import com.positivity.accounting.internal.service.PredicateParser.ParseException;
import com.positivity.accounting.internal.service.PredicateParser.Predicate;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Property-style fuzz test for the E2 predicate parser (issue #946).
 *
 * <p>Throws several hundred adversarial inputs at
 * {@link PredicateParser#parse} — random printable ASCII, truncations of
 * valid predicates, single-character mutations of valid predicates, and
 * random "operator soup" token sequences — asserting the parser's total
 * contract: it either returns a usable AST or throws {@link ParseException},
 * and never hangs, never throws anything else (no NPE, no
 * StackOverflowError, no IndexOutOfBounds), and every successfully parsed
 * predicate evaluates without throwing against arbitrary payload shapes.
 *
 * <p>The random generator is seeded, so the exact case set is deterministic
 * across runs.
 */
@DisplayName("PredicateParser fuzzing (E2)")
class PredicateParserFuzzTest {

    /** Fixed seed — issue number — for deterministic case generation. */
    private static final long SEED = 946L;

    private static final List<String> VALID_PREDICATES = List.of(
            "eventType == 'billing.invoicePosted'",
            "payload.paymentMethod == 'CASH'",
            "payload.amount >= 100.00 && eventType == 'PAYMENT_RECEIVED'",
            "payload.totals.tax > -0.5 && payload.totals.net <= 999.99",
            "payload.a != 'x' && payload.b < 10 && payload.c == +1.25",
            "eventType != 'other.event'",
            "payload.qty > 0",
            "payload.deep.nested.path.value == 'YES'");

    private static final List<String> TOKEN_POOL = List.of(
            "eventType",
            "payload",
            "amount",
            ".",
            "..",
            "==",
            "!=",
            ">",
            ">=",
            "<",
            "<=",
            "&&",
            "&",
            "||",
            "=",
            "'CASH'",
            "''",
            "'",
            "100.5",
            "-3",
            "+",
            "0",
            "9.",
            ".5",
            "_x",
            "*",
            "(",
            ")",
            "\"x\"",
            ";",
            " ");

    private static final Map<String, Object> SAMPLE_PAYLOAD = buildSamplePayload();

    private static Map<String, Object> buildSamplePayload() {
        Map<String, Object> nested = new HashMap<>();
        nested.put("tax", new BigDecimal("8.25"));
        nested.put("net", "91.75");
        Map<String, Object> payload = new HashMap<>();
        payload.put("amount", "100.00");
        payload.put("paymentMethod", "CASH");
        payload.put("qty", 3);
        payload.put("totals", nested);
        payload.put("weird", List.of(1, 2, 3));
        payload.put("flag", Boolean.TRUE);
        payload.put("nil", null);
        return payload;
    }

    @Test
    @DisplayName("parser is total: every input either parses to a usable AST or throws ParseException")
    void parserNeverHangsOrThrowsUnexpectedly() {
        Random random = new Random(SEED);
        List<String> cases = new ArrayList<>();

        // 1. Random printable-ASCII strings, length 0–80
        for (int i = 0; i < 200; i++) {
            int length = random.nextInt(81);
            StringBuilder sb = new StringBuilder(length);
            for (int j = 0; j < length; j++) {
                sb.append((char) (32 + random.nextInt(95)));
            }
            cases.add(sb.toString());
        }

        // 2. Every prefix truncation of every valid predicate (including
        // the full predicate itself, which must parse)
        for (String valid : VALID_PREDICATES) {
            for (int end = 0; end <= valid.length(); end++) {
                cases.add(valid.substring(0, end));
            }
        }

        // 3. Single-character mutations of valid predicates
        for (int i = 0; i < 150; i++) {
            String valid = VALID_PREDICATES.get(random.nextInt(VALID_PREDICATES.size()));
            int position = random.nextInt(valid.length());
            char mutation = (char) (32 + random.nextInt(95));
            cases.add(valid.substring(0, position) + mutation + valid.substring(position + 1));
        }

        // 4. Operator soup: random token sequences from the grammar's vocabulary
        for (int i = 0; i < 150; i++) {
            int tokenCount = 1 + random.nextInt(10);
            StringBuilder sb = new StringBuilder();
            for (int j = 0; j < tokenCount; j++) {
                sb.append(TOKEN_POOL.get(random.nextInt(TOKEN_POOL.size())));
                if (random.nextBoolean()) {
                    sb.append(' ');
                }
            }
            cases.add(sb.toString());
        }

        assertThat(cases).hasSizeGreaterThan(700);

        int parsed = 0;
        int rejected = 0;
        for (String input : cases) {
            try {
                Predicate predicate = PredicateParser.parse(input);
                assertThat(predicate).as("parse('%s') returned AST", input).isNotNull();
                assertThat(predicate.clauses())
                        .as("parse('%s') produced clauses", input)
                        .isNotEmpty();
                // Evaluation of a parsed AST must be total too
                evaluateQuietly(predicate, input);
                parsed++;
            } catch (ParseException expected) {
                assertThat(expected.getMessage())
                        .as("rejection of '%s' carries a message with position info", input)
                        .contains("at position");
                assertThat(expected.getPosition())
                        .as("rejection position of '%s' is within bounds", input)
                        .isBetween(0, input.length());
                rejected++;
            } catch (Throwable unexpected) {
                fail("parse('%s') threw %s instead of ParseException: %s"
                        .formatted(input, unexpected.getClass().getName(), unexpected.getMessage()));
            }
        }

        // Sanity: the corpus exercised both outcomes (valid-prefix
        // truncations include full predicates, so some must parse)
        assertThat(parsed).isPositive();
        assertThat(rejected).isPositive();
    }

    @Test
    @DisplayName("all curated valid predicates parse and evaluate against adversarial payloads")
    void validPredicatesAlwaysParse() {
        for (String valid : VALID_PREDICATES) {
            Predicate predicate = PredicateParser.parse(valid);
            evaluateQuietly(predicate, valid);
        }
    }

    private static void evaluateQuietly(Predicate predicate, String input) {
        try {
            predicate.matches("billing.invoicePosted", SAMPLE_PAYLOAD);
            predicate.matches(null, null);
            predicate.matches("", Map.of());
        } catch (RuntimeException e) {
            fail("evaluating parsed predicate '%s' threw %s: %s"
                    .formatted(input, e.getClass().getName(), e.getMessage()));
        }
    }
}
