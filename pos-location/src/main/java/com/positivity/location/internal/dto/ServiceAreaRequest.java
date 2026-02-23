package com.positivity.location.internal.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request payload for service area endpoints.
 *
 * Issue: #76
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class ServiceAreaRequest {

    private String name;
    private String description;
    private Boolean active;
    private List<PostalCodeEntry> postalCodes;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class PostalCodeEntry {
        private String postalCode;
        private String countryCode;
    }
}
