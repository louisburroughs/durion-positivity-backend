package com.positivity.customer.internal.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.positivity.customer.internal.exception.HouseAccountImmutableException;
import com.positivity.customer.internal.repository.CommercialPartyRepository;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("HouseAccountGuard (CAP:550 S7)")
class HouseAccountGuardTest {

    private static final UUID PARTY_ID = UUID.fromString("01980a58-0000-7000-8000-0000000000c1");

    private final CommercialPartyRepository repository = mock(CommercialPartyRepository.class);
    private final HouseAccountGuard guard = new HouseAccountGuard(repository);

    @Test
    @DisplayName("refuses a write to a house account, naming the party")
    void refusesAHouseAccount() {
        when(repository.existsByPartyIdAndHouseAccountIsNotNull(PARTY_ID)).thenReturn(true);

        assertThatThrownBy(() -> guard.requireNotHouseAccount(PARTY_ID))
                .isInstanceOfSatisfying(
                        HouseAccountImmutableException.class,
                        ex -> org.assertj.core.api.Assertions.assertThat(ex.getPartyId())
                                .isEqualTo(PARTY_ID));
    }

    @Test
    @DisplayName("lets an ordinary party, an unknown id and another tenant's house account through")
    void passesEverythingElse() {
        // An unknown id and another tenant's house account are the same thing to the bound
        // tenant: no visible row. The caller then answers its ordinary not-found (ADR-0062).
        when(repository.existsByPartyIdAndHouseAccountIsNotNull(PARTY_ID)).thenReturn(false);

        assertThatCode(() -> guard.requireNotHouseAccount(PARTY_ID)).doesNotThrowAnyException();
    }
}
