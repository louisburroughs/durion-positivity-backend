package com.positivity.customer.internal.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.customer.config.WebMvcTestSecurityConfig;
import com.positivity.customer.internal.config.CrmExceptionHandler;
import com.positivity.customer.internal.dto.CustomerDTO;
import com.positivity.customer.internal.service.CommercialPartyServiceImpl;
import com.positivity.customer.internal.service.PersonPartyServiceImpl;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-slice tests for {@link CustomerController}.
 *
 * <p>
 * This controller fronts two implementations of the same interface — one for
 * commercial parties, one for individuals — and picks between them per request.
 * Three things make that worth testing at the web layer rather than trusting:
 *
 * <ul>
 * <li>Both dependencies are {@code CustomerService}, so the fields are
 * type-identical. The constructor takes them in the opposite order to the order
 * it assigns them, which is correct today and would still compile if it were
 * not. Swapping them produces a service that answers every question about the
 * wrong kind of customer.</li>
 * <li>Routing is {@code "COMMERCIAL".equalsIgnoreCase(customerType)}, so
 * anything that is not exactly that word — including a typo — silently falls
 * through to the individual side rather than being rejected.</li>
 * <li>Lookup by id tries commercial first and falls back to individual, so the
 * two are not symmetric even though the rest of the controller treats them as
 * if they were.</li>
 * </ul>
 */
@WebMvcTest(CustomerController.class)
@Import({WebMvcTestSecurityConfig.class, CrmExceptionHandler.class})
@ActiveProfiles("test")
@SuppressWarnings({"java:S6813", "java:S100", "java:S1192"})
@DisplayName("CustomerController — commercial/individual routing")
class CustomerControllerWebMvcTest {

    private static final UUID CUSTOMER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000f1");
    private static final String PATH = "/v1/crm";
    private static final String AUTHORITIES = "X-Authorities";
    private static final String VIEW = "crm:party:view";
    private static final String CREATE = "crm:party:create";
    private static final String EDIT = "crm:party:edit";
    private static final String DEACTIVATE = "crm:party:deactivate";

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    java.time.Clock clock;

    @MockitoBean
    CommercialPartyServiceImpl commercialService;

    @MockitoBean
    PersonPartyServiceImpl personService;

    private static CustomerDTO customer(String type) {
        return CustomerDTO.builder()
                .id(CUSTOMER_ID)
                .customerNumber("C-1001")
                .firstName("Jane")
                .lastName("Smith")
                .customerType(type)
                .build();
    }

    private static Page<CustomerDTO> page(String type) {
        return new PageImpl<>(List.of(customer(type)));
    }

    @ParameterizedTest(name = "customerType={0} routes to the commercial service")
    @ValueSource(strings = {"COMMERCIAL", "commercial", "Commercial"})
    @DisplayName("only the exact word COMMERCIAL, in any case, reaches the commercial service")
    void commercialTypeRoutesToCommercialService(String type) throws Exception {
        when(commercialService.getAllCustomers(any(Pageable.class))).thenReturn(page("COMMERCIAL"));

        mockMvc.perform(get(PATH).param("customerType", type).header(AUTHORITIES, VIEW))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].customerType").value("COMMERCIAL"));

        verify(commercialService).getAllCustomers(any(Pageable.class));
        verifyNoInteractions(personService);
    }

    @ParameterizedTest(name = "customerType={0} falls through to the individual service")
    @ValueSource(strings = {"PERSON", "COMERCIAL", "business", ""})
    @DisplayName("anything that is not COMMERCIAL falls through to individuals, including a typo")
    void everythingElseRoutesToPersonService(String type) throws Exception {
        when(personService.getAllCustomers(any(Pageable.class))).thenReturn(page("PERSON"));

        mockMvc.perform(get(PATH).param("customerType", type).header(AUTHORITIES, VIEW))
                .andExpect(status().isOk());

        // "COMERCIAL" is the case worth staring at. A caller who misspells the type is not
        // told so — they are quietly served the individual customer list, which looks like a
        // real answer and is the wrong one. Pinned as current behaviour; making it a 400 would
        // be a contract change, not a bug fix.
        verify(personService).getAllCustomers(any(Pageable.class));
        verifyNoInteractions(commercialService);
    }

    @Test
    @DisplayName("with no customerType at all, the default is individuals")
    void defaultTypeIsPerson() throws Exception {
        when(personService.getAllCustomers(any(Pageable.class))).thenReturn(page("PERSON"));

        mockMvc.perform(get(PATH).header(AUTHORITIES, VIEW)).andExpect(status().isOk());

        verify(personService).getAllCustomers(any(Pageable.class));
        verifyNoInteractions(commercialService);
    }

    @ParameterizedTest(name = "name={0} email={1} searches rather than lists")
    @CsvSource({"Smith,", ",jane@example.com", "Smith,jane@example.com"})
    @DisplayName("a name or email filter switches the list endpoint into a search")
    void filtersSwitchToSearch(String name, String email) throws Exception {
        when(personService.searchCustomers(any(), any(), any(Pageable.class))).thenReturn(page("PERSON"));

        var request = get(PATH).header(AUTHORITIES, VIEW);
        if (name != null) {
            request = request.param("name", name);
        }
        if (email != null) {
            request = request.param("email", email);
        }

        mockMvc.perform(request).andExpect(status().isOk());

        // Either filter alone is enough; the two are an OR, not an AND.
        verify(personService).searchCustomers(any(), any(), any(Pageable.class));
        verify(personService, never()).getAllCustomers(any(Pageable.class));
    }

    @Test
    @DisplayName("a blank filter is not a filter — it still lists rather than searching")
    void blankFilterStillLists() throws Exception {
        when(personService.getAllCustomers(any(Pageable.class))).thenReturn(page("PERSON"));

        // A UI that always sends its search box, empty or not, must not be pushed down the
        // search path on every page load.
        mockMvc.perform(get(PATH).param("name", "   ").header(AUTHORITIES, VIEW))
                .andExpect(status().isOk());

        verify(personService).getAllCustomers(any(Pageable.class));
        verify(personService, never()).searchCustomers(any(), any(), any(Pageable.class));
    }

    @Test
    @DisplayName("lookup by id tries commercial first and falls back to individual")
    void lookupFallsBackFromCommercialToPerson() throws Exception {
        when(commercialService.getCustomerById(CUSTOMER_ID)).thenReturn(Optional.empty());
        when(personService.getCustomerById(CUSTOMER_ID)).thenReturn(Optional.of(customer("PERSON")));

        mockMvc.perform(get(PATH + "/" + CUSTOMER_ID).header(AUTHORITIES, VIEW))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerType").value("PERSON"));

        // Unlike every other endpoint here, this one does not take a type — it asks both. The
        // order is the contract: commercial wins if an id somehow resolves on both sides.
        verify(commercialService).getCustomerById(CUSTOMER_ID);
        verify(personService).getCustomerById(CUSTOMER_ID);
    }

    @Test
    @DisplayName("an id known to neither side is a 404")
    void unknownIdIsNotFound() throws Exception {
        when(commercialService.getCustomerById(CUSTOMER_ID)).thenReturn(Optional.empty());
        when(personService.getCustomerById(CUSTOMER_ID)).thenReturn(Optional.empty());

        mockMvc.perform(get(PATH + "/" + CUSTOMER_ID).header(AUTHORITIES, VIEW)).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("create routes on the body's customerType, not a query parameter")
    void createRoutesOnBodyType() throws Exception {
        when(commercialService.createCustomer(any())).thenReturn(customer("COMMERCIAL"));

        mockMvc.perform(post(PATH)
                        .header(AUTHORITIES, CREATE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerType\":\"COMMERCIAL\",\"firstName\":\"Jane\",\"lastName\":\"Smith\"}"))
                .andExpect(status().isCreated());

        verify(commercialService).createCustomer(any());
        verify(personService, never()).createCustomer(any());
    }

    @ParameterizedTest(name = "{0} is refused to a holder of {1}")
    @CsvSource({
        "create,     crm:party:view",
        "update,     crm:party:view",
        "deactivate, crm:party:edit",
    })
    @DisplayName("each write is gated on its own authority, and viewing grants none of them")
    void writesAreGatedIndividually(String action, String insufficient) throws Exception {
        String body = "{\"customerType\":\"PERSON\",\"firstName\":\"Jane\",\"lastName\":\"Smith\"}";

        switch (action) {
            case "create" ->
                mockMvc.perform(post(PATH)
                                .header(AUTHORITIES, insufficient)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                        .andExpect(status().isForbidden());
            case "update" ->
                mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                                        PATH + "/" + CUSTOMER_ID)
                                .header(AUTHORITIES, insufficient)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                        .andExpect(status().isForbidden());
            // Deactivating is refused to someone who may edit: removing a customer from
            // circulation is a heavier act than correcting their details.
            default ->
                mockMvc.perform(delete(PATH + "/" + CUSTOMER_ID).header(AUTHORITIES, insufficient))
                        .andExpect(status().isForbidden());
        }

        verifyNoInteractions(commercialService);
        verifyNoInteractions(personService);
    }

    @Test
    @DisplayName("deactivating with the right authority answers 204")
    void deactivateReturnsNoContent() throws Exception {
        when(personService.deleteCustomer(eq(CUSTOMER_ID))).thenReturn(true);

        mockMvc.perform(delete(PATH + "/" + CUSTOMER_ID).header(AUTHORITIES, DEACTIVATE))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("updating with the right authority reaches the individual service")
    void updateReachesService() throws Exception {
        when(personService.updateCustomer(eq(CUSTOMER_ID), any())).thenReturn(Optional.of(customer("PERSON")));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                                PATH + "/" + CUSTOMER_ID)
                        .header(AUTHORITIES, EDIT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerType\":\"PERSON\",\"firstName\":\"Jane\",\"lastName\":\"Smith\"}"))
                .andExpect(status().isOk());
    }
}
