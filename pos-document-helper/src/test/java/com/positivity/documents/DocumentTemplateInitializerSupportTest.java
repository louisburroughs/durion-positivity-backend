package com.positivity.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DocumentTemplateInitializerSupport} (ADR-0020).
 *
 * <p>
 * This runs inside {@code ApplicationRunner}s at service startup, so its one
 * hard obligation is the startup-safety contract the repo applies to every
 * registrar: a failing registration is counted and logged, and the loop moves
 * on. One bad template — or pos-documents being down entirely — must not stop
 * the remaining templates from registering, and must never block startup.
 */
@DisplayName("DocumentTemplateInitializerSupport — startup template registration")
class DocumentTemplateInitializerSupportTest {

    private static TemplateRegistration template(String id) {
        return TemplateRegistration.htmlTemplate(id, "d", "<html/>").build();
    }

    @Test
    @DisplayName("registers every template and reports the count")
    void registersAll() {
        List<String> registered = new ArrayList<>();

        int count = new DocumentTemplateInitializerSupport("pos-test")
                .registerTemplates(
                        List.of(template("A"), template("B"), template("C")),
                        registration -> registered.add(registration.getTemplateId()));

        assertThat(count).isEqualTo(3);
        assertThat(registered).containsExactly("A", "B", "C");
    }

    @Test
    @DisplayName("keeps registering after a failure and counts only the successes")
    void oneFailureDoesNotStopTheRest() {
        List<String> registered = new ArrayList<>();

        int count = new DocumentTemplateInitializerSupport("pos-test")
                .registerTemplates(List.of(template("A"), template("BROKEN"), template("C")), registration -> {
                    if ("BROKEN".equals(registration.getTemplateId())) {
                        throw new IllegalStateException("boom");
                    }
                    registered.add(registration.getTemplateId());
                });

        // The startup-safety contract: one bad template must not take the others down with it,
        // and the exception must not escape into the ApplicationRunner.
        assertThat(count).isEqualTo(2);
        assertThat(registered).containsExactly("A", "C");
    }

    @Test
    @DisplayName("survives every registration failing, as when pos-documents is down at startup")
    void totalOutageIsSurvivable() {
        int count = new DocumentTemplateInitializerSupport("pos-test")
                .registerTemplates(List.of(template("A"), template("B")), registration -> {
                    throw new IllegalStateException("connection refused");
                });

        assertThat(count).isZero();
    }

    @Test
    @DisplayName("reports zero for an empty template collection")
    void emptyCollection() {
        assertThat(new DocumentTemplateInitializerSupport("pos-test").registerTemplates(List.of(), registration -> {}))
                .isZero();
    }

    @Test
    @DisplayName("rejects null inputs outright — a misconfigured caller is a bug, not an outage")
    void nullInputsAreRejected() {
        DocumentTemplateInitializerSupport support = new DocumentTemplateInitializerSupport("pos-test");

        assertThatThrownBy(() -> support.registerTemplates(null, registration -> {}))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> support.registerTemplates(List.of(), null)).isInstanceOf(NullPointerException.class);
    }
}
