package com.positivity.customer.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.customer.PostgresIntegrationTestBase;
import com.positivity.customer.internal.dto.CreateCommercialAccountRequest;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

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
 *
 * <p>Rolled back rather than committed. This class shares its database with every other
 * Postgres-backed class in the module, under one tenant, so ten committed parties would be ten rows
 * the next class sees — enough to make any unfiltered party assertion order-dependent. Rollback
 * costs this test nothing: a sequence advances outside the transaction, so the numbers are still
 * drawn the way production draws them and still have to be distinct.
 */
@Transactional
@DisplayName("Commercial customer numbers are unique within a single timestamp window")
class CommercialCustomerNumberIT extends PostgresIntegrationTestBase {

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
            // COMMERCIAL is the path under test, and the service's own default.
            request.setPartyType("COMMERCIAL");

            // Before the fix this threw on the second iteration: same customer number, unique
            // constraint. The loop is what the old code could not survive.
            customerNumbers.add(partyService.createCommercialAccount(request).getCustomerNumber());
        }

        assertThat(customerNumbers)
                .as("every account must carry its own customer number")
                .doesNotHaveDuplicates()
                .allSatisfy(number -> assertThat(number).matches("CUST-[0-9A-Z]{8}"));

        // The sequence starts beyond anything the old scheme could render, so a generated
        // number can never equal a historical one. 'FFFFFFFF' read as base-36 is the largest
        // value 8 hex characters can reach.
        long largestHistoricalValue = Long.parseLong("FFFFFFFF", 36);
        assertThat(customerNumbers)
                .as("generated numbers must sit above every value the UUID-derived scheme could produce")
                .allSatisfy(number -> assertThat(Long.parseLong(number.substring("CUST-".length()), 36))
                        .isGreaterThan(largestHistoricalValue));
    }
}
