package com.positivity.customer.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.customer.internal.dto.InquiryResponse;
import com.positivity.customer.internal.dto.SubmitInquiryRequest;
import com.positivity.customer.internal.entity.Inquiry;
import com.positivity.customer.internal.enums.AudienceType;
import com.positivity.customer.internal.enums.InquiryChannel;
import com.positivity.customer.internal.enums.InquiryStatus;
import com.positivity.customer.internal.exception.CrmTooManyRequestsException;
import com.positivity.customer.internal.exception.CrmUnprocessableEntityException;
import com.positivity.customer.internal.repository.CommercialPartyRepository;
import com.positivity.customer.internal.repository.InquiryRepository;
import com.positivity.customer.internal.repository.PersonPartyRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Story #1154: inquiry capture, the public form's ceiling, and conversion. */
@ExtendWith(MockitoExtension.class)
class InquiryServiceImplTest {

    private static final UUID INQUIRY_ID = UUID.fromString("01960003-0000-7000-8000-000000000030");
    private static final UUID PARTY = UUID.fromString("01960003-0000-7000-8000-000000000031");
    private static final String FINGERPRINT = "abc123";

    private final Clock clock = Clock.fixed(Instant.parse("2026-07-28T12:00:00Z"), ZoneOffset.UTC);

    @Mock
    private InquiryRepository inquiryRepository;

    @Mock
    private CommercialPartyRepository commercialPartyRepository;

    @Mock
    private PersonPartyRepository personPartyRepository;

    @Mock
    private PartyService partyService;

    private InquiryServiceImpl service() {
        return new InquiryServiceImpl(
                clock, inquiryRepository, commercialPartyRepository, personPartyRepository, partyService, 5);
    }

    private static SubmitInquiryRequest request(AudienceType audienceType, String email, String phone) {
        return new SubmitInquiryRequest(
                InquiryChannel.WEB_FORM, audienceType, "Jane Doe", "Acme Towing", email, phone, null, null, null, null);
    }

    private static Inquiry stored(InquiryStatus status, AudienceType audienceType) {
        return Inquiry.builder()
                .inquiryId(INQUIRY_ID)
                .channel(InquiryChannel.WEB_FORM)
                .audienceType(audienceType)
                .contactName("Jane Doe")
                .organizationName("Acme Towing")
                .email("jane@example.com")
                .status(status)
                .build();
    }

    @Test
    @DisplayName("the public form refuses a submitter over the per-source ceiling")
    void publicFormEnforcesCeiling() {
        when(inquiryRepository.countRecentFromSource(anyString(), any())).thenReturn(5L);

        assertThatThrownBy(() -> service()
                        .captureFromPublicForm(request(AudienceType.INDIVIDUAL, "jane@example.com", null), FINGERPRINT))
                .isInstanceOf(CrmTooManyRequestsException.class);
        verify(inquiryRepository, never()).save(any());
    }

    @Test
    @DisplayName("a submission under the ceiling is stored with only the fingerprint, never a raw address")
    void publicFormStoresFingerprintOnly() {
        when(inquiryRepository.countRecentFromSource(anyString(), any())).thenReturn(1L);
        when(inquiryRepository.save(any(Inquiry.class))).thenAnswer(inv -> {
            Inquiry saved = inv.getArgument(0);
            saved.setInquiryId(INQUIRY_ID);
            return saved;
        });

        UUID id = service()
                .captureFromPublicForm(request(AudienceType.INDIVIDUAL, "jane@example.com", null), FINGERPRINT);

        assertThat(id).isEqualTo(INQUIRY_ID);
    }

    @Test
    @DisplayName("an inquiry with no email and no phone is rejected rather than reaching the database")
    void requiresAContactChannel() {
        assertThatThrownBy(() -> service().capture(request(AudienceType.INDIVIDUAL, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("email address or a phone number");
        verify(inquiryRepository, never()).save(any());
    }

    @Test
    @DisplayName("CONVERTED cannot be set as a bare status change")
    void convertedIsNotASettableStatus() {
        assertThatThrownBy(() -> service().updateStatus(INQUIRY_ID, InquiryStatus.CONVERTED, null))
                .isInstanceOf(CrmUnprocessableEntityException.class)
                .hasMessageContaining("converting the inquiry");
    }

    @Test
    @DisplayName("an already-converted inquiry cannot be converted again")
    void cannotReconvert() {
        when(inquiryRepository.findById(INQUIRY_ID))
                .thenReturn(Optional.of(stored(InquiryStatus.CONVERTED, AudienceType.COMMERCIAL)));

        assertThatThrownBy(() -> service().convert(INQUIRY_ID, PARTY))
                .isInstanceOf(CrmUnprocessableEntityException.class)
                .hasMessageContaining("Cannot move inquiry from CONVERTED");
    }

    @Test
    @DisplayName("linking an existing party converts without creating a duplicate record")
    void convertLinksExistingParty() {
        when(inquiryRepository.findById(INQUIRY_ID))
                .thenReturn(Optional.of(stored(InquiryStatus.NEW, AudienceType.COMMERCIAL)));
        when(commercialPartyRepository.existsById(PARTY)).thenReturn(true);
        when(inquiryRepository.save(any(Inquiry.class))).thenAnswer(inv -> inv.getArgument(0));

        InquiryResponse response = service().convert(INQUIRY_ID, PARTY);

        assertThat(response.status()).isEqualTo("CONVERTED");
        assertThat(response.partyId()).isEqualTo(PARTY);
        verify(partyService, never()).createCommercialAccount(any());
    }

    @Test
    @DisplayName("an individual inquiry cannot mint a person party from unverified form input")
    void individualConversionRequiresAnExistingParty() {
        lenient()
                .when(inquiryRepository.findById(INQUIRY_ID))
                .thenReturn(Optional.of(stored(InquiryStatus.NEW, AudienceType.INDIVIDUAL)));

        assertThatThrownBy(() -> service().convert(INQUIRY_ID, null))
                .isInstanceOf(CrmUnprocessableEntityException.class)
                .hasMessageContaining("pos-people-contact");
    }
}
