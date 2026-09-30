package com.positivity.accounting.internal.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link ReportExportArtifact} overrides the record-generated equals/hashCode/toString
 * so the byte[] content is compared by value rather than by array identity.
 */
@DisplayName("ReportExportArtifact")
class ReportExportArtifactTest {

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static ReportExportArtifact csv(String content) {
        return new ReportExportArtifact(bytes(content), "text/csv", "report.csv");
    }

    @Test
    @DisplayName("artifacts with distinct but equal content arrays are equal and share a hash code")
    void equalContentArrays_areEqual() {
        ReportExportArtifact a = csv("a,b\n1,2\n");
        ReportExportArtifact b = csv("a,b\n1,2\n");

        assertThat(a.content()).isNotSameAs(b.content());
        assertThat(a).isEqualTo(b);
        assertThat(b).isEqualTo(a);
        assertThat(a).hasSameHashCodeAs(b);
    }

    @Test
    @DisplayName("an artifact is equal to itself")
    void sameInstance_isEqual() {
        ReportExportArtifact a = csv("x");

        assertThat(a.equals(a)).isTrue();
    }

    @Test
    @DisplayName("an artifact is not equal to null or to another type")
    void nullOrOtherType_notEqual() {
        ReportExportArtifact a = csv("x");

        assertThat(a.equals(null)).isFalse();
        assertThat(a.equals("x")).isFalse();
    }

    @Test
    @DisplayName("differing content bytes make artifacts unequal")
    void differentContent_notEqual() {
        ReportExportArtifact a = csv("a,b\n1,2\n");
        ReportExportArtifact b = csv("a,b\n1,3\n");

        assertThat(a).isNotEqualTo(b);
        assertThat(a.hashCode()).isNotEqualTo(b.hashCode());
    }

    @Test
    @DisplayName("differing content type makes artifacts unequal")
    void differentContentType_notEqual() {
        ReportExportArtifact a = new ReportExportArtifact(bytes("x"), "text/csv", "report.csv");
        ReportExportArtifact b = new ReportExportArtifact(bytes("x"), "application/pdf", "report.csv");

        assertThat(a).isNotEqualTo(b);
        assertThat(a.hashCode()).isNotEqualTo(b.hashCode());
    }

    @Test
    @DisplayName("differing filename makes artifacts unequal")
    void differentFilename_notEqual() {
        ReportExportArtifact a = new ReportExportArtifact(bytes("x"), "text/csv", "report.csv");
        ReportExportArtifact b = new ReportExportArtifact(bytes("x"), "text/csv", "other.csv");

        assertThat(a).isNotEqualTo(b);
        assertThat(a.hashCode()).isNotEqualTo(b.hashCode());
    }

    @Test
    @DisplayName("toString renders the content bytes by value alongside type and filename")
    void toString_rendersContentByValue() {
        ReportExportArtifact a = new ReportExportArtifact(new byte[] {1, 2, 3}, "text/csv", "r.csv");

        assertThat(a.toString())
                .isEqualTo("ReportExportArtifact[content=[1, 2, 3], contentType=text/csv, filename=r.csv]");
    }
}
