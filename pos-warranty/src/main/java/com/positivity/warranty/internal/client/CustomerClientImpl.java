package com.positivity.warranty.internal.client;

import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * RestClient implementation of {@link CustomerClient}.
 *
 * <p>pos-customer CRM reads demand authority {@code crm:party:view}.
 */
@Slf4j
@Component
public class CustomerClientImpl implements CustomerClient {

    private static final String SERVICE_USER = "pos-warranty-service";
    private static final String AUTHORITIES = "crm:party:view";

    private final RestClient restClient;
    private final String basePath;

    public CustomerClientImpl(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder restClientBuilder,
            @Value("${pos.customer.service-id:customer}") String serviceId,
            @Value("${pos.customer.base-path:/v1/crm}") String basePath) {
        this.basePath = basePath;
        this.restClient = restClientBuilder.baseUrl("http://" + serviceId).build();
    }

    @Override
    public @NonNull Optional<CustomerInfo> getCustomer(@NonNull UUID customerId) {
        try {
            CustomerWire response = restClient
                    .get()
                    .uri(basePath + "/{customerId}", customerId)
                    .header("X-User", SERVICE_USER)
                    .header("X-Authorities", AUTHORITIES)
                    .retrieve()
                    .body(CustomerWire.class);
            if (response == null) {
                return Optional.empty();
            }
            String displayName = Stream.of(response.firstName(), response.lastName())
                    .filter(part -> part != null && !part.isBlank())
                    .collect(Collectors.joining(" "));
            return Optional.of(
                    new CustomerInfo(response.id(), response.partyId(), displayName, response.customerNumber()));
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == 404) {
                log.debug("Customer {} not found in CRM", customerId);
            } else {
                log.warn(
                        "Customer lookup failed for customerId={}: HTTP {}",
                        customerId,
                        ex.getStatusCode().value());
            }
            return Optional.empty();
        } catch (RestClientException ex) {
            log.warn("pos-customer unreachable for customerId={}: {}", customerId, ex.getMessage());
            return Optional.empty();
        }
    }

    // -----------------------------------------------------------------------
    // Wire shape (mirrors pos-customer CustomerDTO fields warranty needs;
    // unknown properties ignored)
    // -----------------------------------------------------------------------

    private record CustomerWire(UUID id, UUID partyId, String customerNumber, String firstName, String lastName) {}
}
