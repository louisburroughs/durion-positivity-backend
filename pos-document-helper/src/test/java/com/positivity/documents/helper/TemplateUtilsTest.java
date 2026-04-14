package com.positivity.documents.helper;

import static org.assertj.core.api.Assertions.*;

import java.io.IOException;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for TemplateUtils.
 *
 * Issue: ADR-0020
 */
class TemplateUtilsTest {

    @Test
    void shouldValidateValidTemplateId() {
        assertThatNoException().isThrownBy(() -> TemplateUtils.validateTemplateId("VALID_TEMPLATE_ID"));
        assertThatNoException().isThrownBy(() -> TemplateUtils.validateTemplateId("TEMPLATE-123"));
        assertThatNoException().isThrownBy(() -> TemplateUtils.validateTemplateId("ABC_123-XYZ"));
    }

    @Test
    void shouldRejectInvalidTemplateIds() {
        assertThatThrownBy(() -> TemplateUtils.validateTemplateId("lowercase"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("uppercase");

        assertThatThrownBy(() -> TemplateUtils.validateTemplateId("WITH SPACES"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> TemplateUtils.validateTemplateId("WITH.DOT"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> TemplateUtils.validateTemplateId(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blank");
    }

    @Test
    void shouldValidateValidVersion() {
        assertThatNoException().isThrownBy(() -> TemplateUtils.validateVersion("1.0.0"));
        assertThatNoException().isThrownBy(() -> TemplateUtils.validateVersion("10.20.30"));
        assertThatNoException().isThrownBy(() -> TemplateUtils.validateVersion("0.0.1"));
    }

    @Test
    void shouldRejectInvalidVersions() {
        assertThatThrownBy(() -> TemplateUtils.validateVersion("1.0"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("semantic versioning");

        assertThatThrownBy(() -> TemplateUtils.validateVersion("v1.0.0")).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> TemplateUtils.validateVersion("1.0.0-SNAPSHOT"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldCreateTemplateBuilder() {
        var builder = TemplateUtils.templateBuilder("TEST_TEMPLATE", "pos-test");

        TemplateRegistration registration =
                builder.content("<html></html>").description("Test").build();

        assertThat(registration.getTemplateId()).isEqualTo("TEST_TEMPLATE");
        assertThat(registration.getOwner()).isEqualTo("pos-test");
        assertThat(registration.getVersion()).isEqualTo("1.0.0");
        assertThat(registration.isActive()).isTrue();
    }

    @Test
    void shouldRejectNullTemplateId() {
        assertThatThrownBy(() -> TemplateUtils.templateBuilder(null, "pos-test"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullOwner() {
        assertThatThrownBy(() -> TemplateUtils.templateBuilder("VALID_ID", null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldThrowWhenTemplateResourceNotFound() {
        assertThatThrownBy(() -> TemplateUtils.loadTemplateFromClasspath("nonexistent/template.html"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("not found");
    }
}
