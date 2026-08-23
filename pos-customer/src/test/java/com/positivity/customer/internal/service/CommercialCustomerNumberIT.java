package com.positivity.customer.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.customer.internal.dto.CreateCommercialAccountRequest;
import com.positivity.customer.service.PartyService;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Pins that commercial accounts created in quick succession each get their own customer number.
 *
 * <p>They did not. The number was the first 8 hex characters of a fresh UUIDv7, which are the top
 * 32 bits of its millisecond timestamp - the same value for every id minted within roughly 65
 * seconds. Since {@code customer_number} is UNIQUE, exactly one commercial account could be created
 * per window and every other attempt failed on the constraint, surfacing as a 500. On alpha that
 * measured as one success in ten.
 *
 * <p>Runs against a real Postgres so the sequence and the unique constraint are the ones that
 * ship: the default H2 profile disables Flyway, so neither exists there.
 */
@SpringBootTest
@ActiveProfiles("pg")
@Testcontainers
@DisplayName("Commercial customer numbers are unique within a single timestamp window")
class CommercialCustomerNumberIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private PartyService partyService;

    @Test
    @DisplayName("ten accounts created back to back all persist, with ten distinct numbers")
    void backToBackAccountsGetDistinctCustomerNumbers() {
        List<String> customerNumbers = new ArrayList<>();

        for (int i = 0; i < 10; i++) {
            CreateCommercialAccountRequest request = new CreateCommercialAccountRequest();
            request.setLegalName("Back to back " + i);
            request.setDisplayName("Back to back " + i);
            request.setPartyType("PERSON");

            // Before the fix this threw on the second iteration: same customer number, unique
            // constraint. The loop is what the old code could not survive.
            customerNumbers.add(partyService.createCommercialAccount(request).getCustomerNumber());
        }

        assertThat(customerNumbers)
                .as("every account must carry its own customer number")
                .doesNotHaveDuplicates()
                .allSatisfy(number -> assertThat(number).matches("CUST-[0-9A-Z]{8}"));
    }
}
