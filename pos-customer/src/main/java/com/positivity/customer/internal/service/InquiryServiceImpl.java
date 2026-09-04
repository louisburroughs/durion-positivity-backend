package com.positivity.customer.internal.service;

import com.positivity.customer.internal.dto.CreateCommercialAccountRequest;
import com.positivity.customer.internal.dto.InquiryResponse;
import com.positivity.customer.internal.dto.PagedResponse;
import com.positivity.customer.internal.dto.SubmitInquiryRequest;
import com.positivity.customer.internal.entity.CommercialParty;
import com.positivity.customer.internal.entity.Inquiry;
import com.positivity.customer.internal.enums.AudienceType;
import com.positivity.customer.internal.enums.InquiryStatus;
import com.positivity.customer.internal.enums.LifecycleStage;
import com.positivity.customer.internal.exception.CrmResourceNotFoundException;
import com.positivity.customer.internal.exception.CrmTooManyRequestsException;
import com.positivity.customer.internal.exception.CrmUnprocessableEntityException;
import com.positivity.customer.internal.exception.CrmValidationException;
import com.positivity.customer.internal.repository.CommercialPartyRepository;
import com.positivity.customer.internal.repository.InquiryRepository;
import com.positivity.customer.internal.repository.PersonPartyRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
public class InquiryServiceImpl implements InquiryService {

    private static final String INQUIRY = "Inquiry";
    private static final int MAX_PAGE_SIZE = 200;

    private final Clock clock;
    private final InquiryRepository inquiryRepository;
    private final CommercialPartyRepository commercialPartyRepository;
    private final PersonPartyRepository personPartyRepository;
    private final PartyService partyService;
    private final int publicSubmissionsPerHour;

    public InquiryServiceImpl(
            Clock clock,
            InquiryRepository inquiryRepository,
            CommercialPartyRepository commercialPartyRepository,
            PersonPartyRepository personPartyRepository,
            PartyService partyService,
            @Value("${pos.customer.inquiry.public.max-submissions-per-hour:5}") int publicSubmissionsPerHour) {
        this.clock = clock;
        this.inquiryRepository = inquiryRepository;
        this.commercialPartyRepository = commercialPartyRepository;
        this.personPartyRepository = personPartyRepository;
        this.partyService = partyService;
        this.publicSubmissionsPerHour = publicSubmissionsPerHour;
    }

    @Override
    @Transactional
    public @NonNull InquiryResponse capture(@NonNull SubmitInquiryRequest request) {
        return toResponse(inquiryRepository.save(build(request, null)));
    }

    @Override
    @Transactional
    public @NonNull UUID captureFromPublicForm(
            @NonNull SubmitInquiryRequest request, @Nullable String sourceFingerprint) {
        // An unauthenticated write endpoint needs a ceiling that survives a restart and is
        // shared across instances, so the count comes from the table rather than in-process
        // state. A null fingerprint means the origin could not be determined; those are not
        // rate-limited here and must be bounded at the edge.
        if (sourceFingerprint != null) {
            long recent = inquiryRepository.countRecentFromSource(
                    sourceFingerprint, Instant.now(clock).minus(Duration.ofHours(1)));
            if (recent >= publicSubmissionsPerHour) {
                log.warn("Public inquiry rate limit hit for source fingerprint {}", sourceFingerprint);
                throw new CrmTooManyRequestsException("Too many inquiries submitted recently; please try again later");
            }
        }
        Inquiry saved = inquiryRepository.save(build(request, sourceFingerprint));
        log.info("Public inquiry {} captured via {}", saved.getInquiryId(), saved.getChannel());
        return saved.getInquiryId();
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull InquiryResponse get(@NonNull UUID inquiryId) {
        return toResponse(requireInquiry(inquiryId));
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull PagedResponse<InquiryResponse> list(@Nullable InquiryStatus status, int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE));
        return PagedResponse.from(
                status == null
                        ? inquiryRepository.findAllByOrderByCreatedAtDesc(pageable)
                        : inquiryRepository.findByStatusOrderByCreatedAtDesc(status, pageable),
                InquiryServiceImpl::toResponse);
    }

    @Override
    @Transactional
    public @NonNull InquiryResponse assign(@NonNull UUID inquiryId, @Nullable String assignedTo) {
        Inquiry inquiry = requireInquiry(inquiryId);
        inquiry.setAssignedTo(assignedTo);
        return toResponse(inquiryRepository.save(inquiry));
    }

    @Override
    @Transactional
    public @NonNull InquiryResponse updateStatus(
            @NonNull UUID inquiryId, @NonNull InquiryStatus status, @Nullable String resolutionNote) {
        if (status == InquiryStatus.CONVERTED) {
            // Conversion creates or links a party; letting it be set as a bare status change
            // would leave an inquiry claiming a customer that does not exist.
            throw new CrmUnprocessableEntityException(
                    "CONVERTED is reached by converting the inquiry, not by setting the status directly");
        }
        Inquiry inquiry = requireInquiry(inquiryId);
        assertTransition(inquiry, status);
        inquiry.setStatus(status);
        if (resolutionNote != null) {
            inquiry.setResolutionNote(resolutionNote);
        }
        return toResponse(inquiryRepository.save(inquiry));
    }

    @Override
    @Transactional
    public @NonNull InquiryResponse convert(@NonNull UUID inquiryId, @Nullable UUID existingPartyId) {
        Inquiry inquiry = requireInquiry(inquiryId);
        assertTransition(inquiry, InquiryStatus.CONVERTED);

        UUID partyId = existingPartyId != null ? linkExisting(existingPartyId) : createProspectParty(inquiry);
        inquiry.setPartyId(partyId);
        inquiry.setStatus(InquiryStatus.CONVERTED);
        Inquiry saved = inquiryRepository.save(inquiry);
        log.info("Inquiry {} converted to party {}", inquiryId, partyId);
        return toResponse(saved);
    }

    private UUID linkExisting(UUID partyId) {
        if (!commercialPartyRepository.existsById(partyId) && !personPartyRepository.existsById(partyId)) {
            throw new CrmResourceNotFoundException("Party", partyId);
        }
        return partyId;
    }

    /**
     * Individual inquiries cannot yet be converted to a person party from here: creating one
     * requires a canonical person in pos-people-contact (ADR-0015), and inventing that identity
     * from unverified form input is exactly what this model avoids. Until that path exists, an
     * individual inquiry is linked to an existing party by a CSR.
     */
    private UUID createProspectParty(Inquiry inquiry) {
        if (inquiry.getAudienceType() != AudienceType.COMMERCIAL) {
            throw new CrmUnprocessableEntityException(
                    "An individual inquiry must be converted by linking an existing party; creating a person "
                            + "party requires a verified identity in pos-people-contact");
        }
        String legalName =
                inquiry.getOrganizationName() != null ? inquiry.getOrganizationName() : inquiry.getContactName();
        CreateCommercialAccountRequest request = new CreateCommercialAccountRequest();
        request.setLegalName(legalName);
        request.setDisplayName(legalName);
        request.setContactFirstName(firstName(inquiry.getContactName()));
        request.setContactLastName(lastName(inquiry.getContactName()));
        request.setEmail(inquiry.getEmail());
        request.setPhone(inquiry.getPhone());

        UUID partyId =
                UUID.fromString(partyService.createCommercialAccount(request).getPartyId());
        // Created from an unverified enquiry with no service history — a prospect, not a
        // customer, until they actually buy something.
        commercialPartyRepository.findById(partyId).ifPresent((CommercialParty party) -> {
            party.setLifecycleStage(LifecycleStage.PROSPECT);
            commercialPartyRepository.save(party);
        });
        return partyId;
    }

    private Inquiry build(SubmitInquiryRequest request, @Nullable String sourceFingerprint) {
        // Enforced on both capture paths, and mirrored by a CHECK constraint in V25. An inquiry
        // with no way to reach the enquirer cannot be actioned, and letting it reach the
        // database would surface as a 500 rather than a validation error.
        if (isBlank(request.getEmail()) && isBlank(request.getPhone())) {
            throw new CrmValidationException("An inquiry must include an email address or a phone number");
        }
        return Inquiry.builder()
                .channel(request.getChannel())
                .audienceType(request.getAudienceType())
                .contactName(request.getContactName().trim())
                .organizationName(request.getOrganizationName())
                .email(request.getEmail())
                .phone(request.getPhone())
                .vehicleOfInterest(request.getVehicleOfInterest())
                .serviceOfInterest(request.getServiceOfInterest())
                .message(request.getMessage())
                .campaignCode(request.getCampaignCode())
                .status(InquiryStatus.NEW)
                // Left unassigned on capture: new inquiries land in the shared queue and are
                // picked up deliberately, rather than being silently owned by whoever typed them in.
                .submittedFrom(sourceFingerprint)
                .build();
    }

    private static void assertTransition(Inquiry inquiry, InquiryStatus target) {
        if (!inquiry.getStatus().canTransitionTo(target)) {
            throw new CrmUnprocessableEntityException(
                    "Cannot move inquiry from " + inquiry.getStatus() + " to " + target);
        }
    }

    private Inquiry requireInquiry(UUID inquiryId) {
        return inquiryRepository
                .findById(inquiryId)
                .orElseThrow(() -> new CrmResourceNotFoundException(INQUIRY, inquiryId));
    }

    private static boolean isBlank(@Nullable String value) {
        return value == null || value.isBlank();
    }

    private static String firstName(String contactName) {
        String trimmed = contactName.trim();
        int space = trimmed.indexOf(' ');
        return space < 0 ? trimmed : trimmed.substring(0, space);
    }

    private static String lastName(String contactName) {
        String trimmed = contactName.trim();
        int space = trimmed.indexOf(' ');
        return space < 0 ? trimmed : trimmed.substring(space + 1).trim();
    }

    private static InquiryResponse toResponse(Inquiry inquiry) {
        return new InquiryResponse(
                inquiry.getInquiryId(),
                inquiry.getChannel().name(),
                inquiry.getAudienceType().name(),
                inquiry.getContactName(),
                inquiry.getOrganizationName(),
                inquiry.getEmail(),
                inquiry.getPhone(),
                inquiry.getVehicleOfInterest(),
                inquiry.getServiceOfInterest(),
                inquiry.getMessage(),
                inquiry.getCampaignCode(),
                inquiry.getStatus().name(),
                inquiry.getPartyId(),
                inquiry.getAssignedTo(),
                inquiry.getResolutionNote(),
                inquiry.getCreatedAt());
    }
}
