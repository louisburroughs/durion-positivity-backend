package com.positivity.supplier.internal.client;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * A {@link TaxRegistrationSchemeClient} mock answering pos-tax's placeholder vocabulary (#2623), for the slice tests
 * that create vendors with registrations. A test re-stubs the bean to refuse or to fail.
 */
@TestConfiguration
public class TaxRegistrationSchemeStubConfig {

    /** The shipped placeholder list of pos-tax: US EIN/SSN/ITIN, CA BN/GST_HST/QST(QC)/SIN. */
    public static TaxRegistrationSchemes placeholderVocabulary() {
        return new TaxRegistrationSchemes(
                "STUB",
                List.of(
                        scheme("BN", "CA", "BUSINESS", false, List.of()),
                        scheme("GST_HST", "CA", "BUSINESS", false, List.of()),
                        scheme("QST", "CA", "BUSINESS", true, List.of("QC")),
                        scheme("SIN", "CA", "PERSON", false, List.of()),
                        scheme("EIN", "US", "BUSINESS", false, List.of()),
                        scheme("ITIN", "US", "PERSON", false, List.of()),
                        scheme("SSN", "US", "PERSON", false, List.of())));
    }

    private static TaxRegistrationSchemes.Scheme scheme(
            String code, String country, String holderType, boolean regional, List<String> regions) {
        return new TaxRegistrationSchemes.Scheme(code, country, code + " (placeholder)", holderType, regional, regions);
    }

    @Bean
    TaxRegistrationSchemeClient taxRegistrationSchemeClient() {
        TaxRegistrationSchemeClient client = mock(TaxRegistrationSchemeClient.class);
        when(client.fetch(any())).thenReturn(placeholderVocabulary());
        return client;
    }
}
