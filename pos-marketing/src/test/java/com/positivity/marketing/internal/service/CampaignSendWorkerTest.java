package com.positivity.marketing.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.marketing.internal.entity.Campaign;
import com.positivity.marketing.internal.entity.CampaignSend;
import com.positivity.marketing.internal.entity.MessageTemplate;
import com.positivity.marketing.internal.enums.AudienceType;
import com.positivity.marketing.internal.enums.CampaignChannel;
import com.positivity.marketing.internal.enums.CampaignStatus;
import com.positivity.marketing.internal.enums.SendStatus;
import com.positivity.marketing.internal.repository.CampaignRepository;
import com.positivity.marketing.internal.repository.CampaignSendRepository;
import com.positivity.marketing.internal.repository.MessageTemplateRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Pins the send worker's fail-closed contract at the level that actually matters.
 *
 * <p>An earlier revision of this class documented "fail closed" in a comment while returning
 * null on an unavailable consent check, which let the send proceed. Asserting the behaviour
 * only in {@code AudienceEligibilityServiceTest} would not have caught that — the bug was in
 * how the worker <em>used</em> the decision. These tests exercise the worker itself.
 */
@ExtendWith(MockitoExtension.class)
class CampaignSendWorkerTest {

    private static final UUID CAMPAIGN_ID = UUID.fromString("01960003-0000-7000-8000-000000000050");
    private static final UUID PARTY = UUID.fromString("01960003-0000-7000-8000-000000000051");
    private static final UUID TEMPLATE_ID = UUID.fromString("01960003-0000-7000-8000-000000000052");

    private final Clock clock = Clock.fixed(Instant.parse("2026-07-29T09:00:00Z"), ZoneOffset.UTC);

    @Mock
    private CampaignSendRepository sendRepository;

    @Mock
    private CampaignRepository campaignRepository;

    @Mock
    private MessageTemplateRepository templateRepository;

    @Mock
    private AudienceEligibilityService eligibilityService;

    @Mock
    private MessageChannelPort messageChannel;

    @Mock
    private MarketingFactPublisher factPublisher;

    private CampaignSendWorker worker() {
        return new CampaignSendWorker(
                clock,
                sendRepository,
                campaignRepository,
                templateRepository,
                eligibilityService,
                messageChannel,
                new TemplateRenderService(),
                factPublisher,
                100,
                3);
    }

    private static CampaignSend pendingSend() {
        return CampaignSend.builder()
                .campaignSendId(UUID.fromString("01960003-0000-7000-8000-000000000053"))
                .campaignId(CAMPAIGN_ID)
                .recipientPartyId(PARTY)
                .channel(CampaignChannel.EMAIL)
                .status(SendStatus.PENDING)
                .queuedAt(Instant.parse("2026-07-29T08:00:00Z"))
                .updatedAt(Instant.parse("2026-07-29T08:00:00Z"))
                .build();
    }

    private static Campaign sendingCampaign() {
        return Campaign.builder()
                .campaignId(CAMPAIGN_ID)
                .code("SPRING-2026")
                .name("Spring")
                .audienceType(AudienceType.INDIVIDUAL)
                .status(CampaignStatus.SENDING)
                .channels(Set.of(CampaignChannel.EMAIL))
                .emailTemplateId(TEMPLATE_ID)
                .build();
    }

    private static MessageTemplate emailTemplate() {
        return MessageTemplate.builder()
                .templateId(TEMPLATE_ID)
                .name("Spring email")
                .channel(CampaignChannel.EMAIL)
                .audienceType(AudienceType.INDIVIDUAL)
                .subject("Your service is due")
                .body("Use {{campaignCode}}")
                .build();
    }

    private void givenOneQueuedSend() {
        when(sendRepository.findByStatusOrderByQueuedAtAsc(any(), any())).thenReturn(List.of(pendingSend()));
        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.of(sendingCampaign()));
        when(templateRepository.findById(TEMPLATE_ID)).thenReturn(Optional.of(emailTemplate()));
    }

    @Test
    @DisplayName("a denied decision never reaches the transport and never emits a sent fact")
    void deniedRecipientIsNotContacted() {
        givenOneQueuedSend();
        when(eligibilityService.decide(PARTY, CampaignChannel.EMAIL))
                .thenReturn(new AudienceEligibilityService.Decision(false, "CONSENT_OPT_OUT"));

        worker().drain();

        verifyNoInteractions(messageChannel);
        verify(factPublisher, never()).campaignSent(any(), anyString(), any());

        ArgumentCaptor<CampaignSend> saved = ArgumentCaptor.forClass(CampaignSend.class);
        verify(sendRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(SendStatus.SUPPRESSED);
        assertThat(saved.getValue().getFailureReason()).isEqualTo("CONSENT_OPT_OUT");
    }

    @Test
    @DisplayName("missing consent data is refused, not treated as permission")
    void missingConsentDataIsNotContacted() {
        givenOneQueuedSend();
        when(eligibilityService.decide(PARTY, CampaignChannel.EMAIL))
                .thenReturn(new AudienceEligibilityService.Decision(
                        false, AudienceEligibilityService.REASON_NO_CONSENT_DATA));

        worker().drain();

        verifyNoInteractions(messageChannel);
        verify(factPublisher, never()).campaignSent(any(), anyString(), any());
    }

    @Test
    @DisplayName("a stale replicated decision is refused, and the reason says so")
    void staleConsentIsNotContacted() {
        givenOneQueuedSend();
        when(eligibilityService.decide(PARTY, CampaignChannel.EMAIL))
                .thenReturn(new AudienceEligibilityService.Decision(
                        false, AudienceEligibilityService.REASON_CONSENT_STALE));

        worker().drain();

        verifyNoInteractions(messageChannel);
        ArgumentCaptor<CampaignSend> saved = ArgumentCaptor.forClass(CampaignSend.class);
        verify(sendRepository).save(saved.capture());
        // Distinct from an opt-out so an operator reads this as a replication alert rather than
        // a marketing outcome.
        assertThat(saved.getValue().getFailureReason()).isEqualTo(AudienceEligibilityService.REASON_CONSENT_STALE);
    }

    @Test
    @DisplayName("an allowed decision does send, and records the provider id")
    void allowedRecipientIsContacted() {
        givenOneQueuedSend();
        when(eligibilityService.decide(PARTY, CampaignChannel.EMAIL))
                .thenReturn(new AudienceEligibilityService.Decision(true, "OPT_IN"));
        when(messageChannel.send(any())).thenReturn(MessageChannelPort.SendOutcome.accepted("provider-1", "hash-1"));

        worker().drain();

        ArgumentCaptor<MessageChannelPort.OutboundMessage> outbound =
                ArgumentCaptor.forClass(MessageChannelPort.OutboundMessage.class);
        verify(messageChannel).send(outbound.capture());
        // The send record's id is the FI-2 idempotency key; losing it would let a retried
        // HTTP call become a second email.
        assertThat(outbound.getValue().campaignSendId()).isEqualTo(pendingSend().getCampaignSendId());
        assertThat(outbound.getValue().campaignCode()).isEqualTo("SPRING-2026");

        ArgumentCaptor<CampaignSend> saved = ArgumentCaptor.forClass(CampaignSend.class);
        verify(sendRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(SendStatus.SENT);
        assertThat(saved.getValue().getProviderMessageId()).isEqualTo("provider-1");
        assertThat(saved.getValue().getAddressHash()).isEqualTo("hash-1");
        verify(factPublisher).campaignSent(any(), anyString(), any());
    }

    @Test
    @DisplayName("a paused campaign stops mid-flight rather than draining its backlog")
    void pausedCampaignIsSkipped() {
        Campaign paused = sendingCampaign();
        paused.setStatus(CampaignStatus.PAUSED);
        when(sendRepository.findByStatusOrderByQueuedAtAsc(any(), any())).thenReturn(List.of(pendingSend()));
        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.of(paused));

        worker().drain();

        verifyNoInteractions(messageChannel);
        verifyNoInteractions(eligibilityService);
    }
}
