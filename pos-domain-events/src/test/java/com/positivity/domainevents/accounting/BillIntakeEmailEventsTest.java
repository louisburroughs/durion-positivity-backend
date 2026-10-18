package com.positivity.domainevents.accounting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.domainevents.accounting.BillIntakeEmailProcessedV1.OutcomeKind;
import com.positivity.domainevents.accounting.BillIntakeEmailReceivedV1.Attachment;
import com.positivity.domainevents.accounting.BillIntakeEmailReceivedV1.RefusedAttachment;
import com.positivity.domainevents.accounting.BillIntakeEmailReceivedV1.Status;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The two payloads of CAP:550 S27 (#2520, ruling 6). The cross-cutting {@code DomainEventContractTest}
 * sweeps both records; this pins what is specific to them: the address never travels as text, the
 * CONFIDENTIAL fields stay out of {@code toString()}, and the cross-field rules hold.
 */
class BillIntakeEmailEventsTest {

    private static final UUID ID = UUID.fromString("01990000-0000-7000-8000-0000000000d1");
    private static final Instant AT = Instant.parse("2026-10-17T12:00:00Z");
    private static final String SENDER = "owner@vendor.example";
    private static final String SUBJECT = "Invoice 4471 for October";
    private static final String FILE = "invoice-4471.pdf";

    private static BillIntakeEmailReceivedV1 accepted(List<Attachment> attachments) {
        return new BillIntakeEmailReceivedV1(
                ID, ID, ID, AT, SENDER, SUBJECT, Status.ACCEPTED, null, null, attachments, List.of());
    }

    private static Attachment attachment() {
        return new Attachment(ID, FILE, "application/pdf", 1024L, "a".repeat(64));
    }

    @Test
    @DisplayName("the command is published under its contract type and carries the address id, never the text")
    void commandWireShape() {
        JsonNode json = new ObjectMapper().valueToTree(accepted(List.of(attachment())));

        assertThat(BillIntakeEmailReceivedV1.EVENT_TYPE).isEqualTo("accounting.bill-intake.email-received");
        assertThat(BillIntakeEmailReceivedV1.SCHEMA_VERSION).isEqualTo(1);
        assertThat(json.has("receivingAddressId")).isTrue();
        assertThat(json.has("receivingAddress"))
                .as("the address text is a capability")
                .isFalse();
        assertThat(json.has("rejectionReason"))
                .as("absent fields are omitted, not null")
                .isFalse();
        assertThat(json.path("attachments").get(0).path("ref").asString()).isEqualTo(ID.toString());
    }

    @Test
    @DisplayName("sender, subject and file names stay out of toString (ADR-0072)")
    void confidentialFieldsAreNotPrinted() {
        BillIntakeEmailReceivedV1 command = new BillIntakeEmailReceivedV1(
                ID,
                ID,
                ID,
                AT,
                SENDER,
                SUBJECT,
                Status.ACCEPTED,
                null,
                null,
                List.of(attachment()),
                List.of(new RefusedAttachment("huge-scan.tiff", "FILE_TOO_LARGE")));

        assertThat(command.toString()).doesNotContain(SENDER, SUBJECT, FILE, "huge-scan");
        assertThat(attachment().toString()).doesNotContain(FILE);
        assertThat(new RefusedAttachment("huge-scan.tiff", "FILE_TOO_LARGE").toString())
                .doesNotContain("huge-scan");
    }

    @Test
    @DisplayName("a rejected command needs a reason; a rate-limit summary needs a positive count")
    void rejectionRules() {
        assertThatThrownBy(() -> new BillIntakeEmailReceivedV1(
                        ID, ID, ID, AT, SENDER, SUBJECT, Status.REJECTED, " ", null, List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BillIntakeEmailReceivedV1(
                        ID, ID, ID, AT, null, null, Status.REJECTED, "RATE_LIMITED", 0, List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);

        BillIntakeEmailReceivedV1 summary = new BillIntakeEmailReceivedV1(
                ID, ID, ID, AT, null, null, Status.REJECTED, "RATE_LIMITED", 5, List.of(), List.of());
        assertThat(summary.rateLimitedCount()).isEqualTo(5);
        assertThat(summary.fromAddress()).isNull();
    }

    @Test
    @DisplayName("the subject is stripped of control characters and cut to 200 characters")
    void subjectIsCleaned() {
        BillIntakeEmailReceivedV1 command = new BillIntakeEmailReceivedV1(
                ID,
                ID,
                ID,
                AT,
                SENDER,
                "Bill\r\nBcc: x\u0000" + "y".repeat(300),
                Status.ACCEPTED,
                null,
                null,
                List.of(),
                List.of());

        assertThat(command.subject()).hasSize(BillIntakeEmailReceivedV1.MAX_SUBJECT_LENGTH);
        assertThat(command.subject()).doesNotContain("\r", "\n", "\u0000");
    }

    @Test
    @DisplayName("a message carries at most 20 attachments")
    void attachmentCap() {
        List<Attachment> twentyOne = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            twentyOne.add(attachment());
        }
        assertThatThrownBy(() -> accepted(twentyOne)).isInstanceOf(IllegalArgumentException.class);
        assertThat(accepted(twentyOne.subList(0, 20)).attachments()).hasSize(20);
    }

    @Test
    @DisplayName("an attachment must name its digest, size and type")
    void attachmentValidation() {
        assertThatThrownBy(() -> new Attachment(ID, FILE, "application/pdf", -1L, "a".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Attachment(ID, FILE, "application/pdf", 1L, " "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Attachment(ID, " ", "application/pdf", 1L, "a".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the result is published under its contract type and a draft outcome names its intake")
    void resultShapeAndRules() {
        BillIntakeEmailProcessedV1 result = new BillIntakeEmailProcessedV1(
                ID,
                ID,
                BillIntakeEmailProcessedV1.Status.ACCEPTED,
                null,
                List.of(
                        new BillIntakeEmailProcessedV1.Outcome(ID, OutcomeKind.DRAFT_CREATED, ID),
                        new BillIntakeEmailProcessedV1.Outcome(ID, OutcomeKind.ATTACHMENT_UNAVAILABLE, null)));

        assertThat(BillIntakeEmailProcessedV1.EVENT_TYPE).isEqualTo("accounting.bill-intake.email-processed");
        assertThat(BillIntakeEmailProcessedV1.SCHEMA_VERSION).isEqualTo(1);
        assertThat(result.outcomes()).hasSize(2);
        assertThatThrownBy(() -> new BillIntakeEmailProcessedV1.Outcome(ID, OutcomeKind.DRAFT_CREATED, null))
                .isInstanceOf(IllegalArgumentException.class);

        JsonNode json = new ObjectMapper().valueToTree(result);
        assertThat(json.path("outcomes").get(1).has("intakeId")).isFalse();
        assertThat(json.has("reason")).isFalse();
    }

    @Test
    @DisplayName("a rejected command is settled with an empty outcome list")
    void rejectedResultHasNoOutcomes() {
        BillIntakeEmailProcessedV1 result = new BillIntakeEmailProcessedV1(
                ID, ID, BillIntakeEmailProcessedV1.Status.REJECTED, "NO_ATTACHMENT", null);

        assertThat(result.outcomes()).isEmpty();
    }
}
