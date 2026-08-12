package com.positivity.marketing.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.marketing.internal.domain.TemplateToken;
import com.positivity.marketing.internal.dto.MessageTemplateResponse;
import com.positivity.marketing.internal.dto.UpsertMessageTemplateRequest;
import com.positivity.marketing.internal.entity.Campaign;
import com.positivity.marketing.internal.entity.MessageTemplate;
import com.positivity.marketing.internal.enums.AudienceType;
import com.positivity.marketing.internal.enums.CampaignChannel;
import com.positivity.marketing.internal.exception.MarketingDuplicateResourceException;
import com.positivity.marketing.internal.exception.MarketingResourceNotFoundException;
import com.positivity.marketing.internal.exception.MarketingUnprocessableEntityException;
import com.positivity.marketing.internal.repository.CampaignRepository;
import com.positivity.marketing.internal.repository.MessageTemplateRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link MessageTemplateServiceImpl}.
 *
 * <p>
 * A template is shared state: one edit reaches every campaign bound to it and
 * every recipient of those campaigns. The rules that matter here all exist to
 * stop one edit breaking many sends:
 *
 * <ul>
 * <li><b>Channel and audience type are immutable.</b> Flipping either silently
 * invalidates campaigns already bound to the template — an SMS campaign would
 * hold an email body, a fleet campaign a template written with individual
 * tokens.</li>
 * <li><b>An in-use template cannot be deleted.</b> The failure would otherwise
 * surface at dispatch, across the whole audience at once.</li>
 * <li><b>SMS templates carry no subject.</b> The field is nulled on write, not
 * merely ignored on render, so nothing downstream can resurrect it.</li>
 * <li><b>Names are unique case-insensitively, but a template does not collide
 * with itself</b> — renaming to a different case must stay possible.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MessageTemplateServiceImpl — template CRUD and immutability rules")
class MessageTemplateServiceImplTest {

    private static final UUID TEMPLATE_ID = UUID.randomUUID();

    @Mock
    private MessageTemplateRepository templateRepository;

    @Mock
    private CampaignRepository campaignRepository;

    @Mock
    private TemplateRenderService renderService;

    @InjectMocks
    private MessageTemplateServiceImpl service;

    private static UpsertMessageTemplateRequest request(
            String name, CampaignChannel channel, AudienceType audienceType, String subject, String body) {
        return new UpsertMessageTemplateRequest(name, channel, audienceType, subject, body);
    }

    private static MessageTemplate existing(CampaignChannel channel, AudienceType audienceType) {
        return MessageTemplate.builder()
                .templateId(TEMPLATE_ID)
                .name("Spring reminder")
                .channel(channel)
                .audienceType(audienceType)
                .subject(channel == CampaignChannel.SMS ? null : "Time for a tune-up")
                .body("Hello")
                .build();
    }

    @Nested
    @DisplayName("create")
    class Create {

        @Test
        @DisplayName("rejects a name already taken, before rendering is even validated")
        void duplicateName() {
            when(templateRepository.existsByNameIgnoreCase("Spring reminder")).thenReturn(true);

            assertThatThrownBy(() -> service.create(
                            request("  Spring reminder  ", CampaignChannel.EMAIL, AudienceType.INDIVIDUAL, "Hi", "Hi")))
                    .isInstanceOf(MarketingDuplicateResourceException.class);

            verifyNoInteractions(renderService);
            verify(templateRepository, never()).save(any());
        }

        @Test
        @DisplayName("trims the name, validates the tokens, and stamps the current user")
        void savesTrimmedAndValidated() {
            when(templateRepository.existsByNameIgnoreCase("Spring reminder")).thenReturn(false);
            when(templateRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

            service.create(request(
                    "  Spring reminder  ",
                    CampaignChannel.EMAIL,
                    AudienceType.INDIVIDUAL,
                    "Time for a tune-up",
                    "Hello {{firstName}}"));

            verify(renderService)
                    .validate(
                            CampaignChannel.EMAIL,
                            AudienceType.INDIVIDUAL,
                            "Time for a tune-up",
                            "Hello {{firstName}}");
            MessageTemplate saved = captureSaved();
            assertThat(saved.getName()).isEqualTo("Spring reminder");
            assertThat(saved.getSubject()).isEqualTo("Time for a tune-up");
            assertThat(saved.getBody()).isEqualTo("Hello {{firstName}}");
            // No authenticated principal in a unit test, so the documented fallback applies.
            assertThat(saved.getCreatedBy()).isEqualTo("system");
        }

        @Test
        @DisplayName("drops the subject on an SMS template so it cannot resurface downstream")
        void smsSubjectIsNulled() {
            when(templateRepository.existsByNameIgnoreCase(any())).thenReturn(false);
            when(templateRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

            service.create(request("Blast", CampaignChannel.SMS, AudienceType.INDIVIDUAL, null, "Short body"));

            assertThat(captureSaved().getSubject()).isNull();
        }

        @Test
        @DisplayName("does not save when token validation rejects the body")
        void validationFailureBlocksSave() {
            when(templateRepository.existsByNameIgnoreCase(any())).thenReturn(false);
            doThrow(new MarketingUnprocessableEntityException("unknown token"))
                    .when(renderService)
                    .validate(any(), any(), any(), any());

            assertThatThrownBy(() -> service.create(
                            request("Blast", CampaignChannel.EMAIL, AudienceType.COMMERCIAL, "Hi", "{{nope}}")))
                    .isInstanceOf(MarketingUnprocessableEntityException.class);

            verify(templateRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("update")
    class Update {

        @Test
        @DisplayName("rejects an unknown template")
        void unknownTemplate() {
            when(templateRepository.findById(TEMPLATE_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.update(
                            TEMPLATE_ID, request("New", CampaignChannel.EMAIL, AudienceType.INDIVIDUAL, "Hi", "Hi")))
                    .isInstanceOf(MarketingResourceNotFoundException.class);
        }

        @Test
        @DisplayName("rejects a rename onto another template's name")
        void renameOntoAnother() {
            when(templateRepository.findById(TEMPLATE_ID))
                    .thenReturn(Optional.of(existing(CampaignChannel.EMAIL, AudienceType.INDIVIDUAL)));
            MessageTemplate other = existing(CampaignChannel.EMAIL, AudienceType.INDIVIDUAL);
            other.setTemplateId(UUID.randomUUID());
            when(templateRepository.findByNameIgnoreCase("Autumn check")).thenReturn(Optional.of(other));

            assertThatThrownBy(() -> service.update(
                            TEMPLATE_ID,
                            request("Autumn check", CampaignChannel.EMAIL, AudienceType.INDIVIDUAL, "Hi", "Hi")))
                    .isInstanceOf(MarketingDuplicateResourceException.class);

            verify(templateRepository, never()).save(any());
        }

        @Test
        @DisplayName("allows a template to keep its own name, so a case-only rename still works")
        void doesNotCollideWithItself() {
            MessageTemplate template = existing(CampaignChannel.EMAIL, AudienceType.INDIVIDUAL);
            when(templateRepository.findById(TEMPLATE_ID)).thenReturn(Optional.of(template));
            when(templateRepository.findByNameIgnoreCase("SPRING REMINDER")).thenReturn(Optional.of(template));
            when(templateRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

            MessageTemplateResponse response = service.update(
                    TEMPLATE_ID,
                    request("SPRING REMINDER", CampaignChannel.EMAIL, AudienceType.INDIVIDUAL, "Subject", "Body"));

            assertThat(response.name()).isEqualTo("SPRING REMINDER");
            assertThat(response.subject()).isEqualTo("Subject");
            assertThat(response.body()).isEqualTo("Body");
        }

        @Test
        @DisplayName("refuses to change the channel of an existing template")
        void channelIsImmutable() {
            when(templateRepository.findById(TEMPLATE_ID))
                    .thenReturn(Optional.of(existing(CampaignChannel.EMAIL, AudienceType.INDIVIDUAL)));
            when(templateRepository.findByNameIgnoreCase(any())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.update(
                            TEMPLATE_ID,
                            request("Spring reminder", CampaignChannel.SMS, AudienceType.INDIVIDUAL, null, "Body")))
                    .isInstanceOf(MarketingUnprocessableEntityException.class)
                    .hasMessageContaining("immutable");

            verifyNoInteractions(renderService);
            verify(templateRepository, never()).save(any());
        }

        @Test
        @DisplayName("refuses to change the audience type of an existing template")
        void audienceTypeIsImmutable() {
            when(templateRepository.findById(TEMPLATE_ID))
                    .thenReturn(Optional.of(existing(CampaignChannel.EMAIL, AudienceType.INDIVIDUAL)));
            when(templateRepository.findByNameIgnoreCase(any())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.update(
                            TEMPLATE_ID,
                            request("Spring reminder", CampaignChannel.EMAIL, AudienceType.COMMERCIAL, "Hi", "Body")))
                    .isInstanceOf(MarketingUnprocessableEntityException.class)
                    .hasMessageContaining("immutable");

            verify(templateRepository, never()).save(any());
        }

        @Test
        @DisplayName("nulls the subject when updating an SMS template")
        void smsUpdateDropsSubject() {
            MessageTemplate template = existing(CampaignChannel.SMS, AudienceType.INDIVIDUAL);
            when(templateRepository.findById(TEMPLATE_ID)).thenReturn(Optional.of(template));
            when(templateRepository.findByNameIgnoreCase(any())).thenReturn(Optional.empty());
            when(templateRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

            service.update(
                    TEMPLATE_ID,
                    request("Spring reminder", CampaignChannel.SMS, AudienceType.INDIVIDUAL, "leaked", "Body"));

            assertThat(template.getSubject()).isNull();
        }
    }

    @Nested
    @DisplayName("delete")
    class Delete {

        @Test
        @DisplayName("rejects an unknown template")
        void unknownTemplate() {
            when(templateRepository.findById(TEMPLATE_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.delete(TEMPLATE_ID))
                    .isInstanceOf(MarketingResourceNotFoundException.class);

            verify(templateRepository, never()).delete(any());
        }

        @Test
        @DisplayName("refuses to delete a template an email campaign still points at")
        void inUseAsEmailTemplate() {
            when(templateRepository.findById(TEMPLATE_ID))
                    .thenReturn(Optional.of(existing(CampaignChannel.EMAIL, AudienceType.INDIVIDUAL)));
            when(campaignRepository.findAll())
                    .thenReturn(List.of(Campaign.builder()
                            .campaignId(UUID.randomUUID())
                            .emailTemplateId(TEMPLATE_ID)
                            .build()));

            assertThatThrownBy(() -> service.delete(TEMPLATE_ID))
                    .isInstanceOf(MarketingUnprocessableEntityException.class)
                    .hasMessageContaining("attached to at least one campaign");

            verify(templateRepository, never()).delete(any());
        }

        @Test
        @DisplayName("refuses to delete a template an SMS campaign still points at")
        void inUseAsSmsTemplate() {
            when(templateRepository.findById(TEMPLATE_ID))
                    .thenReturn(Optional.of(existing(CampaignChannel.SMS, AudienceType.INDIVIDUAL)));
            when(campaignRepository.findAll())
                    .thenReturn(List.of(Campaign.builder()
                            .campaignId(UUID.randomUUID())
                            .smsTemplateId(TEMPLATE_ID)
                            .build()));

            assertThatThrownBy(() -> service.delete(TEMPLATE_ID))
                    .isInstanceOf(MarketingUnprocessableEntityException.class);
        }

        @Test
        @DisplayName("deletes a template no campaign references")
        void deletesUnusedTemplate() {
            MessageTemplate template = existing(CampaignChannel.EMAIL, AudienceType.INDIVIDUAL);
            when(templateRepository.findById(TEMPLATE_ID)).thenReturn(Optional.of(template));
            when(campaignRepository.findAll())
                    .thenReturn(List.of(Campaign.builder()
                            .campaignId(UUID.randomUUID())
                            .emailTemplateId(UUID.randomUUID())
                            .build()));

            service.delete(TEMPLATE_ID);

            verify(templateRepository).delete(template);
        }
    }

    @Nested
    @DisplayName("get and list")
    class Reads {

        @Test
        @DisplayName("reports the tokens available to the template's audience alongside the body")
        void getExposesAvailableTokens() {
            when(templateRepository.findById(TEMPLATE_ID))
                    .thenReturn(Optional.of(existing(CampaignChannel.EMAIL, AudienceType.COMMERCIAL)));

            MessageTemplateResponse response = service.get(TEMPLATE_ID);

            assertThat(response.templateId()).isEqualTo(TEMPLATE_ID);
            assertThat(response.channel()).isEqualTo("EMAIL");
            assertThat(response.audienceType()).isEqualTo("COMMERCIAL");
            assertThat(response.availableTokens())
                    .isEqualTo(TemplateToken.namesFor(AudienceType.COMMERCIAL))
                    .isNotEmpty();
        }

        @Test
        @DisplayName("uses the indexed query only when both filters are supplied")
        void bothFiltersUseIndexedQuery() {
            when(templateRepository.findByChannelAndAudienceTypeOrderByNameAsc(
                            CampaignChannel.EMAIL, AudienceType.INDIVIDUAL))
                    .thenReturn(List.of(existing(CampaignChannel.EMAIL, AudienceType.INDIVIDUAL)));

            assertThat(service.list(CampaignChannel.EMAIL, AudienceType.INDIVIDUAL))
                    .hasSize(1);

            verify(templateRepository, never()).findAllByOrderByNameAsc();
        }

        @Test
        @DisplayName("filters in memory when only one of the two filters is supplied")
        void singleFilterStillFilters() {
            when(templateRepository.findAllByOrderByNameAsc())
                    .thenReturn(List.of(
                            existing(CampaignChannel.EMAIL, AudienceType.INDIVIDUAL),
                            existing(CampaignChannel.SMS, AudienceType.INDIVIDUAL),
                            existing(CampaignChannel.EMAIL, AudienceType.COMMERCIAL)));

            assertThat(service.list(CampaignChannel.EMAIL, null))
                    .extracting(MessageTemplateResponse::channel)
                    .containsOnly("EMAIL");
            assertThat(service.list(null, AudienceType.COMMERCIAL))
                    .extracting(MessageTemplateResponse::audienceType)
                    .containsOnly("COMMERCIAL");
        }

        @Test
        @DisplayName("returns everything when no filter is supplied")
        void noFilterReturnsAll() {
            when(templateRepository.findAllByOrderByNameAsc())
                    .thenReturn(List.of(
                            existing(CampaignChannel.EMAIL, AudienceType.INDIVIDUAL),
                            existing(CampaignChannel.SMS, AudienceType.COMMERCIAL)));

            assertThat(service.list(null, null)).hasSize(2);
        }
    }

    private MessageTemplate captureSaved() {
        ArgumentCaptor<MessageTemplate> captor = ArgumentCaptor.forClass(MessageTemplate.class);
        verify(templateRepository).save(captor.capture());
        return captor.getValue();
    }
}
