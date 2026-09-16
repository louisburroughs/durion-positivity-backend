package com.positivity.people.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.people.internal.config.PeopleEventPublisher;
import com.positivity.people.internal.dto.CredentialUpsertCommand;
import com.positivity.people.internal.dto.PersonCredentialResponse;
import com.positivity.people.internal.entity.Employee;
import com.positivity.people.internal.entity.PersonCredential;
import com.positivity.people.internal.entity.Skill;
import com.positivity.people.internal.enums.CredentialStatus;
import com.positivity.people.internal.exception.NotFoundException;
import com.positivity.people.internal.exception.RequestValidationException;
import com.positivity.people.internal.exception.UnknownSkillCodeException;
import com.positivity.people.internal.repository.EmployeeRepository;
import com.positivity.people.internal.repository.PersonCredentialRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** The credential aggregate's rules (CAP-328, spec D7): natural key, derived status, supersession. */
@ExtendWith(MockitoExtension.class)
class PersonCredentialServiceImplTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 16);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-16T12:00:00Z"), ZoneOffset.UTC);
    private static final UUID PERSON = UUID.fromString("00000000-0000-0000-0000-000000000009");
    private static final Skill T4 = Skill.builder()
            .id(UUID.fromString("00000000-0000-0000-0000-0000000000f4"))
            .code("BRAKES-MEDIUM_HEAVY")
            .competenceCode("BRAKES")
            .minGvwrClass(4)
            .maxGvwrClass(8)
            .active(true)
            .build();

    @Mock
    private PersonCredentialRepository repository;

    @Mock
    private EmployeeRepository employeeRepository;

    @Mock
    private SkillRegistryService skillRegistryService;

    @Mock
    private PeopleEventPublisher publisher;

    private PersonCredentialServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new PersonCredentialServiceImpl(repository, employeeRepository, skillRegistryService, publisher, CLOCK);
        lenient().when(employeeRepository.findByPersonId(PERSON)).thenReturn(Optional.of(new Employee()));
        lenient().when(skillRegistryService.resolve("ASE", "T4-BRAKES")).thenReturn(T4);
        lenient().when(repository.save(any())).thenAnswer(invocation -> {
            PersonCredential saved = invocation.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(UUID.randomUUID());
            }
            return saved;
        });
    }

    @Test
    @DisplayName("a new credential is inserted with the derived status, the issuer defaulted to the source, and published")
    void insertsAndPublishes() {
        when(repository.findByPersonIdAndSkill_IdAndIssuerAndIssuedOn(PERSON, T4.getId(), "ASE", LocalDate.of(2024, 3, 15)))
                .thenReturn(Optional.empty());

        PersonCredentialResponse response = service.upsert(PERSON, ase("T4-BRAKES", LocalDate.of(2024, 3, 15), LocalDate.of(2029, 3, 15)), "hr-feed");

        assertThat(response.getSkillCode()).isEqualTo("BRAKES-MEDIUM_HEAVY");
        assertThat(response.getIssuer()).isEqualTo("ASE");
        assertThat(response.getStatus()).isEqualTo("ACTIVE");
        assertThat(response.getMaxGvwrClass()).isEqualTo(8);
        ArgumentCaptor<PersonCredential> captor = ArgumentCaptor.forClass(PersonCredential.class);
        verify(publisher).publishPersonCredentialUpdated(captor.capture());
        assertThat(captor.getValue().getCreatedBy()).isEqualTo("hr-feed");
    }

    @Test
    @DisplayName("a re-send with the same natural key updates the row in place — same id, new expiry")
    void resendUpdatesInPlace() {
        PersonCredential existing = credential(LocalDate.of(2024, 3, 15), LocalDate.of(2029, 3, 15), CredentialStatus.ACTIVE);
        when(repository.findByPersonIdAndSkill_IdAndIssuerAndIssuedOn(PERSON, T4.getId(), "ASE", LocalDate.of(2024, 3, 15)))
                .thenReturn(Optional.of(existing));

        PersonCredentialResponse response = service.upsert(PERSON, ase("T4-BRAKES", LocalDate.of(2024, 3, 15), LocalDate.of(2030, 3, 15)), "hr-feed");

        assertThat(response.getCredentialId()).isEqualTo(existing.getId());
        assertThat(response.getExpiresOn()).isEqualTo(LocalDate.of(2030, 3, 15));
    }

    @Test
    @DisplayName("a renewal — a later issue date — is a new row beside the old one, never an overwrite")
    void renewalIsANewRow() {
        when(repository.findByPersonIdAndSkill_IdAndIssuerAndIssuedOn(PERSON, T4.getId(), "ASE", LocalDate.of(2029, 3, 1)))
                .thenReturn(Optional.empty());

        service.upsert(PERSON, ase("T4-BRAKES", LocalDate.of(2029, 3, 1), LocalDate.of(2034, 3, 1)), "hr-feed");

        ArgumentCaptor<PersonCredential> captor = ArgumentCaptor.forClass(PersonCredential.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getIssuedOn()).isEqualTo(LocalDate.of(2029, 3, 1));
        verify(repository, never()).delete(any());
    }

    @Test
    @DisplayName("status comes from the dates, not the feed: a past expiry is EXPIRED on write and on read")
    void expiredDerivesFromDates() {
        when(repository.findByPersonIdAndSkill_IdAndIssuerAndIssuedOn(any(), any(), any(), any())).thenReturn(Optional.empty());

        PersonCredentialResponse response = service.upsert(PERSON, ase("T4-BRAKES", LocalDate.of(2020, 6, 30), LocalDate.of(2025, 6, 30)), "hr-feed");

        assertThat(response.getStatus()).isEqualTo("EXPIRED");
    }

    @Test
    @DisplayName("no expiry means it does not expire — never EXPIRED")
    void nullExpiryNeverExpires() {
        when(repository.findByPersonIdAndSkill_IdAndIssuerAndIssuedOn(any(), any(), any(), any())).thenReturn(Optional.empty());

        PersonCredentialResponse response = service.upsert(PERSON, ase("T4-BRAKES", LocalDate.of(2010, 1, 1), null), "hr-feed");

        assertThat(response.getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("a revocation is the one thing a re-send does not undo")
    void revokedStaysRevoked() {
        PersonCredential revoked = credential(LocalDate.of(2024, 3, 15), LocalDate.of(2029, 3, 15), CredentialStatus.REVOKED);
        when(repository.findByPersonIdAndSkill_IdAndIssuerAndIssuedOn(PERSON, T4.getId(), "ASE", LocalDate.of(2024, 3, 15)))
                .thenReturn(Optional.of(revoked));

        PersonCredentialResponse response = service.upsert(PERSON, ase("T4-BRAKES", LocalDate.of(2024, 3, 15), LocalDate.of(2029, 3, 15)), "hr-feed");

        assertThat(response.getStatus()).isEqualTo("REVOKED");
    }

    @Test
    @DisplayName("an unknown vendor code fails loudly before anything is written")
    void unknownCodeFailsLoudly() {
        when(skillRegistryService.resolve("ASE", "T3-ALIGN")).thenThrow(new UnknownSkillCodeException("ASE", "T3-ALIGN"));

        assertThatThrownBy(() -> service.upsert(PERSON, ase("T3-ALIGN", LocalDate.of(2024, 1, 1), null), "hr-feed"))
                .isInstanceOf(UnknownSkillCodeException.class);
        verify(repository, never()).save(any());
        verify(publisher, never()).publishPersonCredentialUpdated(any());
    }

    @Test
    @DisplayName("a credential named by skillCode needs an issuer; an expiry before the issue date is refused")
    void validation() {
        when(skillRegistryService.requireByCode("DOT-INSPECTOR")).thenReturn(T4);
        assertThatThrownBy(() -> service.upsert(
                        PERSON,
                        CredentialUpsertCommand.builder().skillCode("DOT-INSPECTOR").issuedOn(LocalDate.of(2025, 1, 1)).build(),
                        "hr-feed"))
                .isInstanceOf(RequestValidationException.class)
                .hasMessageContaining("issuer");
        assertThatThrownBy(() -> service.upsert(PERSON, ase("T4-BRAKES", LocalDate.of(2025, 1, 1), LocalDate.of(2024, 1, 1)), "hr-feed"))
                .isInstanceOf(RequestValidationException.class)
                .hasMessageContaining("expiresOn");
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("an unknown person is 404")
    void unknownPerson() {
        UUID stranger = UUID.randomUUID();
        when(employeeRepository.findByPersonId(stranger)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.upsert(stranger, ase("T4-BRAKES", LocalDate.of(2024, 1, 1), null), "hr-feed"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("supersedeAbsent marks this source's rows the feed no longer sends SUPERSEDED — never deleted, revoked left alone")
    void supersedeAbsent() {
        PersonCredential kept = credential(LocalDate.of(2024, 3, 15), null, CredentialStatus.ACTIVE);
        PersonCredential dropped = credential(LocalDate.of(2022, 1, 1), null, CredentialStatus.ACTIVE);
        PersonCredential revoked = credential(LocalDate.of(2021, 1, 1), null, CredentialStatus.REVOKED);
        when(repository.findByPersonIdAndSourceSystem(PERSON, "bulk-ingest:job-1")).thenReturn(List.of(kept, dropped, revoked));

        int changed = service.supersedeAbsent(PERSON, "bulk-ingest:job-1", Set.of(kept.getId()), "bulk-ingest:job-1");

        assertThat(changed).isEqualTo(1);
        assertThat(dropped.getStatus()).isEqualTo(CredentialStatus.SUPERSEDED);
        assertThat(dropped.getSupersededBy()).isEqualTo("bulk-ingest:job-1");
        assertThat(revoked.getStatus()).isEqualTo(CredentialStatus.REVOKED);
        verify(repository, never()).delete(any());
        verify(publisher, times(1)).publishPersonCredentialUpdated(any());
    }

    @Test
    @DisplayName("listByPerson derives today's status per row and keeps renewals as separate rows")
    void listDerivesStatus() {
        when(repository.findByPersonIdOrderByIssuedOnDesc(PERSON))
                .thenReturn(List.of(
                        credential(LocalDate.of(2029, 3, 1), LocalDate.of(2034, 3, 1), CredentialStatus.ACTIVE),
                        credential(LocalDate.of(2020, 6, 30), LocalDate.of(2025, 6, 30), CredentialStatus.ACTIVE)));

        List<PersonCredentialResponse> rows = service.listByPerson(PERSON);

        assertThat(rows).hasSize(2);
        // Issued in the future relative to today: not yet held, but the dates say nothing expired.
        assertThat(rows.get(0).getStatus()).isEqualTo("ACTIVE");
        assertThat(rows.get(1).getStatus()).isEqualTo("EXPIRED");
    }

    @Test
    @DisplayName("qualifiedOn is the facility-local date test the scheduler will use")
    void qualifiedOn() {
        PersonCredential credential = credential(LocalDate.of(2024, 3, 15), LocalDate.of(2026, 9, 16), CredentialStatus.ACTIVE);
        assertThat(credential.qualifiedOn(LocalDate.of(2026, 9, 16))).isTrue();
        assertThat(credential.qualifiedOn(LocalDate.of(2026, 9, 17))).isFalse();
        assertThat(credential.qualifiedOn(LocalDate.of(2024, 3, 14))).isFalse();
    }

    private static CredentialUpsertCommand ase(String code, LocalDate issuedOn, LocalDate expiresOn) {
        return CredentialUpsertCommand.builder()
                .sourceCode("ASE")
                .sourceCredentialCode(code)
                .issuedOn(issuedOn)
                .expiresOn(expiresOn)
                .proficiency(4)
                .sourceSystem("bulk-ingest:job-1")
                .build();
    }

    private static PersonCredential credential(LocalDate issuedOn, LocalDate expiresOn, CredentialStatus status) {
        return PersonCredential.builder()
                .id(UUID.randomUUID())
                .personId(PERSON)
                .skill(T4)
                .issuer("ASE")
                .sourceCode("ASE")
                .sourceCredentialCode("T4-BRAKES")
                .issuedOn(issuedOn)
                .expiresOn(expiresOn)
                .status(status)
                .sourceSystem("bulk-ingest:job-1")
                .build();
    }
}
