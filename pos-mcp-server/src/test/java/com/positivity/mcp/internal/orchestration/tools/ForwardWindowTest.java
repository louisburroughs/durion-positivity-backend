package com.positivity.mcp.internal.orchestration.tools;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.mcp.internal.orchestration.tools.DateWindowResolver.Comparison;
import com.positivity.mcp.internal.orchestration.tools.DateWindowResolver.ResolvedWindow;
import com.positivity.mcp.internal.orchestration.tools.DateWindowResolver.Shape;
import com.positivity.mcp.internal.orchestration.tools.DateWindowResolver.Unit;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * #1681: q16 ("which vendor bills are due in the next 14 days") asked a clarifying question rather
 * than answering, through three rounds of prompt iteration. The 2026-09-05 gate run recorded the
 * model explaining why in its own words — "the system's date-window helper only resolves windows
 * that end on today" — and it was right. Every shape resolved backward or to-date, so there was no
 * argument it could have sent that meant "the next 14 days". This was a hole in the tool surface
 * reported as a prompt-following failure.
 */
@DisplayName("FORWARD window shape — ranges that end in the future")
class ForwardWindowTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 5);

    @Test
    @DisplayName("q16: the next 14 days starts today and ends 13 days later, inclusive")
    void nextFourteenDays() {
        ResolvedWindow w = DateWindowResolver.resolve(TODAY, Shape.FORWARD, Unit.DAY, 14, Comparison.NONE);

        assertThat(w.startDate()).isEqualTo(LocalDate.of(2026, 9, 5));
        assertThat(w.endDate()).isEqualTo(LocalDate.of(2026, 9, 18));
    }

    @Test
    @DisplayName("the window is inclusive of today, so a bill due today is in scope")
    void includesToday() {
        // A bill due today is due within the next N days by any reading a user would recognise;
        // starting tomorrow would silently drop it from a cash-requirement answer.
        ResolvedWindow w = DateWindowResolver.resolve(TODAY, Shape.FORWARD, Unit.DAY, 1, Comparison.NONE);

        assertThat(w.startDate()).isEqualTo(TODAY);
        assertThat(w.endDate()).isEqualTo(TODAY);
    }

    @Test
    @DisplayName("months are supported for scheduling questions, not only days")
    void forwardMonths() {
        ResolvedWindow w = DateWindowResolver.resolve(TODAY, Shape.FORWARD, Unit.MONTH, 3, Comparison.NONE);

        assertThat(w.startDate()).isEqualTo(TODAY);
        assertThat(w.endDate()).isEqualTo(LocalDate.of(2026, 12, 4));
    }

    @Test
    @DisplayName("the statement names the direction, so the answer cannot read as historical")
    void statementNamesDirection() {
        ResolvedWindow w = DateWindowResolver.resolve(TODAY, Shape.FORWARD, Unit.DAY, 14, Comparison.NONE);

        assertThat(w.statement()).contains("next").contains("2026-09-05").contains("2026-09-18");
    }

    @Test
    @DisplayName("the classifier reads `in the next 14 days` without being told the shape")
    void classifierReadsForwardWording() {
        WindowPhraseClassifier.Classification c = WindowPhraseClassifier.classify(
                        "Which vendor bills are due in the next 14 days, and how much cash do we need?")
                .orElseThrow();

        assertThat(c.shape()).isEqualTo(Shape.FORWARD);
        assertThat(c.unit()).isEqualTo(Unit.DAY);
        assertThat(c.count()).isEqualTo(14);
    }

    @Test
    @DisplayName("`the next three months` is forward too")
    void classifierReadsForwardMonths() {
        WindowPhraseClassifier.Classification c = WindowPhraseClassifier.classify(
                        "appointments in the next three months")
                .orElseThrow();

        assertThat(c.shape()).isEqualTo(Shape.FORWARD);
        assertThat(c.unit()).isEqualTo(Unit.MONTH);
        assertThat(c.count()).isEqualTo(3);
    }

    @Test
    @DisplayName("`the last 14 days` is still backward — forward wording does not leak")
    void backwardWordingUnaffected() {
        assertThat(WindowPhraseClassifier.classify("bills paid in the last 14 days")
                        .orElseThrow()
                        .shape())
                .isEqualTo(Shape.ROLLING);
    }
}
