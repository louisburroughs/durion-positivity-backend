package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.dto.ResolvedDisplayReference;
import com.positivity.accounting.internal.entity.LocationProfile;
import com.positivity.accounting.internal.enums.DisplayReferenceType;
import com.positivity.accounting.internal.repository.ExtCustomerPartyRepository;
import com.positivity.accounting.internal.repository.ExtInvoiceRepository;
import com.positivity.accounting.internal.repository.JournalEntryRepository;
import com.positivity.accounting.internal.repository.LocationProfileRepository;
import com.positivity.accounting.internal.repository.VendorBillRepository;
import com.positivity.accounting.internal.repository.VendorRepository;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for the code-keyed half of display resolution (issue #1797).
 *
 * <p>Accounting's location dimension is a code, not a UUID, so {@code LOCATION} resolves through
 * {@code resolveCodes} against {@code accounting_location_profile.location_code}. These tests pin
 * the matching rules — case-insensitive, keyed back by the caller's own spelling, canonical code
 * as the display reference — and that each entry point rejects the other's key shape rather than
 * silently returning nothing.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DisplayReferenceResolver Tests (issue #1797)")
class DisplayReferenceResolverTest {

    @Mock
    private ExtInvoiceRepository extInvoiceRepository;

    @Mock
    private ExtCustomerPartyRepository extCustomerPartyRepository;

    @Mock
    private JournalEntryRepository journalEntryRepository;

    @Mock
    private LocationProfileRepository locationProfileRepository;

    @Mock
    private VendorRepository vendorRepository;

    @Mock
    private VendorBillRepository vendorBillRepository;

    @InjectMocks
    private DisplayReferenceResolver resolver;

    @Test
    @DisplayName("Location codes resolve case-insensitively and are keyed back by the caller's spelling")
    void resolvesLocationCodesIgnoringCase() {
        when(locationProfileRepository.findByLocationCodeInIgnoreCase(anyCollection()))
                .thenReturn(List.of(profile("LOC-107", "Planta Monterrey")));

        Map<String, ResolvedDisplayReference> resolved =
                resolver.resolveCodes(DisplayReferenceType.LOCATION, List.of("loc-107", "LOC_USA"));

        assertThat(resolved).containsOnlyKeys("loc-107");
        assertThat(resolved.get("loc-107").displayName()).isEqualTo("Planta Monterrey");
        // The display reference is the canonical code as stored, not the producer's spelling.
        assertThat(resolved.get("loc-107").displayReference()).isEqualTo("LOC-107");
    }

    @Test
    @DisplayName("The lookup is one upper-cased IN query, with blanks, nulls and duplicates dropped")
    void batchesOneNormalizedQuery() {
        when(locationProfileRepository.findByLocationCodeInIgnoreCase(anyCollection()))
                .thenReturn(List.of());

        resolver.resolveCodes(
                DisplayReferenceType.LOCATION, Arrays.asList("loc-107", " LOC-107 ", "Loc-107", null, "  ", "loc_usa"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> codes = ArgumentCaptor.forClass(Collection.class);
        verify(locationProfileRepository).findByLocationCodeInIgnoreCase(codes.capture());
        assertThat(codes.getValue()).containsExactlyInAnyOrder("LOC-107", "LOC_USA");
    }

    @Test
    @DisplayName("Nothing to resolve means no query at all")
    void skipsQueryWhenNoUsableCodes() {
        assertThat(resolver.resolveCodes(DisplayReferenceType.LOCATION, Arrays.asList(null, "", "  ")))
                .isEmpty();
        verify(locationProfileRepository, never()).findByLocationCodeInIgnoreCase(anyCollection());
    }

    @Test
    @DisplayName("LOCATION cannot be resolved by UUID: the code-keyed dimension has no UUID to match")
    void rejectsUuidLookupForCodeKeyedType() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> resolver.resolve(DisplayReferenceType.LOCATION, Set.of(UUID.randomUUID())))
                .withMessageContaining("code-keyed");
        verify(locationProfileRepository, never()).findByLocationCodeInIgnoreCase(anyCollection());
    }

    @Test
    @DisplayName("UUID-keyed types cannot be resolved by code")
    void rejectsCodeLookupForUuidKeyedType() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> resolver.resolveCodes(DisplayReferenceType.INVOICE, Set.of("INV-2026-004417")))
                .withMessageContaining("UUID-keyed");
    }

    private static LocationProfile profile(String code, String label) {
        LocationProfile profile = new LocationProfile();
        profile.setLocationCode(code);
        profile.setLocationLabel(label);
        profile.setCurrencyCode("MXN");
        return profile;
    }
}
