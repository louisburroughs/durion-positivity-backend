package com.positivity.peoplecontact.internal.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.peoplecontact.config.TestSecurityConfig;
import com.positivity.peoplecontact.internal.dto.PostalAddressDto;
import com.positivity.peoplecontact.internal.enums.PartyType;
import com.positivity.peoplecontact.service.PostalAddressService;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-slice tests for {@link PostalAddressController}.
 *
 * <p>
 * One controller serves two subjects — people and organizations — over six
 * endpoints that are pairwise identical apart from the {@link PartyType} passed
 * down and the authority required. That symmetry is the risk: the person and
 * organization halves differ by one word in the path, one word in the
 * {@code @PreAuthorize}, and one enum constant, and getting any of the three
 * crossed produces something that works perfectly while reading or writing the
 * wrong party's home address.
 *
 * <p>
 * So the tests check both directions of the boundary: that a person authority
 * does not open an organization endpoint or the reverse, and that each endpoint
 * passes the matching PartyType to the service rather than the other one.
 */
@WebMvcTest(PostalAddressController.class)
@Import(TestSecurityConfig.class)
@ActiveProfiles("test")
@SuppressWarnings({"java:S6813", "java:S100", "java:S1192"})
@DisplayName("PostalAddressController — web slice")
class PostalAddressControllerWebMvcTest {

    private static final UUID PARTY_ID = UUID.fromString("00000000-0000-0000-0000-0000000000e1");
    private static final String AUTHORITIES = "X-Authorities";
    private static final String PERSON_VIEW = "people-contact:person:view";
    private static final String PERSON_EDIT = "people-contact:person:edit";
    private static final String ORG_VIEW = "people-contact:organization:view";
    private static final String ORG_EDIT = "people-contact:organization:edit";
    private static final String PERSON_PATH = "/v1/people/" + PARTY_ID + "/postal-address";
    private static final String ORG_PATH = "/v1/organizations/" + PARTY_ID + "/postal-address";
    private static final String BODY = """
            {"line1":"1 Mill Road","city":"Leeds","postalCode":"LS1 1AA","countryCode":"GB"}""";

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    java.time.Clock clock;

    @MockitoBean
    PostalAddressService postalAddressService;

    private static PostalAddressDto address() {
        PostalAddressDto dto = new PostalAddressDto();
        dto.setLine1("1 Mill Road");
        dto.setCity("Leeds");
        dto.setPostalCode("LS1 1AA");
        dto.setCountryCode("GB");
        return dto;
    }

    @Test
    @DisplayName("GET a person's address returns it and asks the service for a PERSON")
    void getPersonAddressUsesPersonPartyType() throws Exception {
        when(postalAddressService.getAddress(PartyType.PERSON, PARTY_ID)).thenReturn(Optional.of(address()));

        mockMvc.perform(get(PERSON_PATH).header(AUTHORITIES, PERSON_VIEW))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.city").value("Leeds"));

        // Stubbed on PERSON specifically: had the controller passed ORGANIZATION, the stub
        // would not match and the response would be a 404 instead.
        verify(postalAddressService).getAddress(PartyType.PERSON, PARTY_ID);
    }

    @Test
    @DisplayName("GET an organization's address asks the service for an ORGANIZATION")
    void getOrganizationAddressUsesOrganizationPartyType() throws Exception {
        when(postalAddressService.getAddress(PartyType.ORGANIZATION, PARTY_ID)).thenReturn(Optional.of(address()));

        mockMvc.perform(get(ORG_PATH).header(AUTHORITIES, ORG_VIEW)).andExpect(status().isOk());

        verify(postalAddressService).getAddress(PartyType.ORGANIZATION, PARTY_ID);
    }

    @Test
    @DisplayName("a party with no address on file is a 404, not an empty address")
    void missingAddressIsNotFound() throws Exception {
        when(postalAddressService.getAddress(PartyType.PERSON, PARTY_ID)).thenReturn(Optional.empty());

        // An empty 200 body would read downstream as "this person lives nowhere" rather than
        // "we have not recorded where they live".
        mockMvc.perform(get(PERSON_PATH).header(AUTHORITIES, PERSON_VIEW)).andExpect(status().isNotFound());
    }

    @ParameterizedTest(name = "{0} is refused to a holder of {1}")
    @CsvSource({
        // A person authority must not reach an organization's address, or the reverse.
        "/v1/organizations/%s/postal-address, people-contact:person:view",
        "/v1/people/%s/postal-address,        people-contact:organization:view",
    })
    @DisplayName("person and organization authorities do not cross the party boundary on reads")
    void readAuthoritiesDoNotCrossPartyBoundary(String pathTemplate, String authority) throws Exception {
        mockMvc.perform(get(pathTemplate.formatted(PARTY_ID)).header(AUTHORITIES, authority))
                .andExpect(status().isForbidden());

        verifyNoInteractions(postalAddressService);
    }

    @Test
    @DisplayName("writing an address needs an edit authority — viewing it is not enough")
    void writeRequiresEditAuthority() throws Exception {
        when(postalAddressService.putAddress(eq(PartyType.PERSON), eq(PARTY_ID), any()))
                .thenReturn(address());

        mockMvc.perform(put(PERSON_PATH)
                        .header(AUTHORITIES, PERSON_VIEW)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isForbidden());
        verify(postalAddressService, never()).putAddress(any(), any(), any());

        mockMvc.perform(put(PERSON_PATH)
                        .header(AUTHORITIES, PERSON_EDIT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isOk());
        verify(postalAddressService).putAddress(eq(PartyType.PERSON), eq(PARTY_ID), any());
    }

    @Test
    @DisplayName("deleting an address needs an edit authority and answers 204")
    void deleteRequiresEditAuthority() throws Exception {
        mockMvc.perform(delete(ORG_PATH).header(AUTHORITIES, ORG_VIEW)).andExpect(status().isForbidden());
        verify(postalAddressService, never()).deleteAddress(any(), any());

        mockMvc.perform(delete(ORG_PATH).header(AUTHORITIES, ORG_EDIT)).andExpect(status().isNoContent());
        verify(postalAddressService).deleteAddress(PartyType.ORGANIZATION, PARTY_ID);
    }
}
