package com.positivity.mcp.internal.orchestration.tools;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.mcp.internal.orchestration.tools.DateWindowResolver.Comparison;
import com.positivity.mcp.internal.orchestration.tools.DateWindowResolver.Shape;
import com.positivity.mcp.internal.orchestration.tools.DateWindowResolver.Unit;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * #1675: the shape a window takes is decided by the question's own wording, and three rounds of
 * prompt text (#1664, #1670, #1672) failed to make the model apply that rule reliably. The
 * 2026-09-05 gate proved it at the tool boundary: q09, q12 and q15 all reached
 * {@code resolveDateWindow} carrying {@code shape=ROLLING} where the wording says calendar. Both
 * the DATE_WINDOW prompt layer and the tool's own description already state the rule, so a fourth
 * restatement is not the fix. This classifier moves the decision into code, where it is testable.
 */
@DisplayName("WindowPhraseClassifier — deciding shape from wording instead of asking the model")
class WindowPhraseClassifierTest {

    @Nested
    @DisplayName("the preposition is the discriminator (#1670)")
    class Preposition {

        @Test
        @DisplayName("q12: `in the last six months` is a calendar span")
        void inIsCalendar() {
            Optional<WindowPhraseClassifier.Classification> c = WindowPhraseClassifier.classify(
                    "Of the invoices we issued in the last six months, how many " + "were paid within 30 days?");

            assertThat(c).isPresent();
            assertThat(c.get().shape()).isEqualTo(Shape.CALENDAR_SPAN);
            assertThat(c.get().unit()).isEqualTo(Unit.MONTH);
            assertThat(c.get().count()).isEqualTo(6);
        }

        @Test
        @DisplayName("q09: `in the last twelve months` reads the spelled-out count")
        void spelledOutCount() {
            Optional<WindowPhraseClassifier.Classification> c = WindowPhraseClassifier.classify(
                    "For our top 20 customers by revenue in the last twelve months, show revenue");

            assertThat(c).isPresent();
            assertThat(c.get().shape()).isEqualTo(Shape.CALENDAR_SPAN);
            assertThat(c.get().count()).isEqualTo(12);
        }

        @Test
        @DisplayName("`over the last six months` on its own stays rolling")
        void overIsRolling() {
            Optional<WindowPhraseClassifier.Classification> c =
                    WindowPhraseClassifier.classify("Revenue over the last six months");

            assertThat(c).isPresent();
            assertThat(c.get().shape()).isEqualTo(Shape.ROLLING);
        }

        @Test
        @DisplayName("`within the last N months` abstains — the contract assigns it no shape")
        void unlicensedPrepositionAbstains() {
            // Only the prepositions DATE_WINDOW_LAYER_TEXT actually names are recognised. "within"
            // is not one of them, so overriding the model here would be inventing a rule rather
            // than applying one — the exact over-reach this classifier is scoped to avoid.
            assertThat(WindowPhraseClassifier.classify("invoices issued within the last six months"))
                    .isEmpty();
        }

        @Test
        @DisplayName("a day-expressed range is always rolling, whatever the preposition")
        void daysAreAlwaysRolling() {
            assertThat(WindowPhraseClassifier.classify("customers who haven't bought in the last 90 days")
                            .orElseThrow()
                            .shape())
                    .isEqualTo(Shape.ROLLING);
        }
    }

    @Nested
    @DisplayName("mixed comparison resolves both sides on the calendar shape")
    class MixedComparison {

        @Test
        @DisplayName("q15: `over the last six months compared with the same six months last year`")
        void namedComparisonWins() {
            Optional<WindowPhraseClassifier.Classification> c = WindowPhraseClassifier.classify(
                    "Who were our largest vendors by spend over the last six months compared with "
                            + "the same six months last year?");

            assertThat(c).isPresent();
            // "over" alone would be ROLLING; the fixed phrase names a specific period and wins.
            assertThat(c.get().shape()).isEqualTo(Shape.CALENDAR_SPAN);
            assertThat(c.get().comparison()).isEqualTo(Comparison.YEAR_EARLIER);
        }
    }

    @Nested
    @DisplayName("single-period wording")
    class SinglePeriod {

        @Test
        @DisplayName("`last month` is the prior complete month")
        void lastMonth() {
            WindowPhraseClassifier.Classification c = WindowPhraseClassifier.classify(
                            "Who were our top technicians by labor revenue last month?")
                    .orElseThrow();

            assertThat(c.shape()).isEqualTo(Shape.PRIOR_COMPLETE);
            assertThat(c.unit()).isEqualTo(Unit.MONTH);
            assertThat(c.count()).isEqualTo(1);
        }

        @Test
        @DisplayName("`this quarter` is the current period to date")
        void thisQuarter() {
            WindowPhraseClassifier.Classification c =
                    WindowPhraseClassifier.classify("Sales this quarter").orElseThrow();

            assertThat(c.shape()).isEqualTo(Shape.CURRENT_TO_DATE);
            assertThat(c.unit()).isEqualTo(Unit.QUARTER);
        }
    }

    @Nested
    @DisplayName("declining to classify is a real answer")
    class Abstains {

        @Test
        @DisplayName("wording with no relative range yields nothing rather than a guess")
        void noRange() {
            assertThat(WindowPhraseClassifier.classify("How many open work orders do we have?"))
                    .isEmpty();
        }

        @Test
        @DisplayName("an explicit calendar year is not a relative range and is left alone")
        void namedYear() {
            assertThat(WindowPhraseClassifier.classify("What was revenue in 2025?"))
                    .isEmpty();
        }

        @Test
        @DisplayName("null and blank are safe")
        void nullSafe() {
            assertThat(WindowPhraseClassifier.classify(null)).isEmpty();
            assertThat(WindowPhraseClassifier.classify("   ")).isEmpty();
        }
    }
}
