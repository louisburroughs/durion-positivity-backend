package com.positivity.tenant.internal.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.security.common.GatewayAuthoritiesFilter;
import com.positivity.tenant.internal.config.SecurityConfig;
import com.positivity.tenant.internal.dto.AccountContactResponse;
import com.positivity.tenant.internal.dto.AccountResponse;
import com.positivity.tenant.internal.dto.BillingProfileResponse;
import com.positivity.tenant.internal.dto.TenantResponse;
import com.positivity.tenant.internal.enums.ContactRole;
import com.positivity.tenant.internal.enums.TenantStatus;
import com.positivity.tenant.internal.exception.InvalidStatusTransitionException;
import com.positivity.tenant.internal.exception.ResourceNotFoundException;
import com.positivity.tenant.internal.service.AccountService;
import com.positivity.tenant.internal.service.TenantService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Web-layer contract of the platform-admin API through the production gateway security chain:
 * the {@code platform:*} permission gates, validation, and the status codes the service exceptions
 * map to.
 */
@WebMvcTest({PlatformTenantController.class, PlatformAccountController.class})
@Import({SecurityConfig.class, PlatformControllersWebMvcTest.SliceConfig.class})
class PlatformControllersWebMvcTest {

    private static final UUID TENANT_ID = UUID.fromString("01990000-0000-7000-8000-000000000123");
    private static final UUID ACCOUNT_ID = UUID.fromString("01990000-0000-7000-8000-00000000a001");

    @TestConfiguration
    static class SliceConfig {
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-09-10T12:00:00Z"), ZoneOffset.UTC);
        }

        /**
         * MockMvc registers every Filter bean directly, which would run GatewayAuthoritiesFilter
         * before the security chain and mark it already-filtered; disable that so it runs inside
         * gatewaySecurityFilterChain as in production.
         */
        @Bean
        FilterRegistrationBean<GatewayAuthoritiesFilter> gatewayAuthoritiesFilterRegistration(
                GatewayAuthoritiesFilter gatewayAuthoritiesFilter) {
            var registration = new FilterRegistrationBean<>(gatewayAuthoritiesFilter);
            registration.setEnabled(false);
            return registration;
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TenantService tenantService;

    @MockitoBean
    private AccountService accountService;

    private static MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder, String... authorities) {
        return builder.header("X-User", "platform-admin").header("X-Authorities", String.join(",", authorities));
    }

    private static TenantResponse tenant(TenantStatus status) {
        return TenantResponse.builder()
                .id(TENANT_ID)
                .slug("acme")
                .displayName("Acme")
                .status(status)
                .accountId(ACCOUNT_ID)
                .initialAdminEmail("owner@acme.example")
                .build();
    }

    @Test
    void createTenantNeedsThePermission() throws Exception {
        String body = """
                {"slug":"acme","displayName":"Acme","accountId":"%s","initialAdminEmail":"owner@acme.example"}
                """.formatted(ACCOUNT_ID);
        mockMvc.perform(post("/v1/platform/tenants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(authed(
                        post("/v1/platform/tenants")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body),
                        "platform:tenant:read"))
                .andExpect(status().isForbidden());

        when(tenantService.create(any())).thenReturn(tenant(TenantStatus.PENDING));
        mockMvc.perform(authed(
                        post("/v1/platform/tenants")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body),
                        "platform:tenant:create"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.slug").value("acme"));
    }

    @Test
    void createTenantValidatesTheSlugAndEmail() throws Exception {
        String body = """
                {"slug":"Not Valid!","displayName":"Acme","accountId":"%s","initialAdminEmail":"nope"}
                """.formatted(ACCOUNT_ID);
        mockMvc.perform(authed(
                        post("/v1/platform/tenants")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body),
                        "platform:tenant:create"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listAndGetTenants() throws Exception {
        when(tenantService.list(TenantStatus.ACTIVE)).thenReturn(List.of(tenant(TenantStatus.ACTIVE)));
        when(tenantService.get(TENANT_ID)).thenReturn(tenant(TenantStatus.ACTIVE));

        mockMvc.perform(authed(get("/v1/platform/tenants").param("status", "ACTIVE"), "platform:tenant:read"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(TENANT_ID.toString()));
        mockMvc.perform(authed(get("/v1/platform/tenants/{id}", TENANT_ID), "platform:tenant:read"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value(ACCOUNT_ID.toString()));
        mockMvc.perform(authed(get("/v1/platform/tenants/{id}", TENANT_ID), "platform:account:read"))
                .andExpect(status().isForbidden());
    }

    @Test
    void unknownTenantIs404() throws Exception {
        when(tenantService.get(TENANT_ID)).thenThrow(new ResourceNotFoundException("Tenant not found"));
        mockMvc.perform(authed(get("/v1/platform/tenants/{id}", TENANT_ID), "platform:tenant:read"))
                .andExpect(status().isNotFound());
    }

    @Test
    void lifecycleOperationsEachHaveTheirOwnPermission() throws Exception {
        when(tenantService.suspend(TENANT_ID)).thenReturn(tenant(TenantStatus.SUSPENDED));
        when(tenantService.reactivate(TENANT_ID)).thenReturn(tenant(TenantStatus.ACTIVE));
        when(tenantService.decommission(TENANT_ID)).thenReturn(tenant(TenantStatus.DECOMMISSIONED));

        mockMvc.perform(authed(post("/v1/platform/tenants/{id}/suspend", TENANT_ID), "platform:tenant:suspend"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUSPENDED"));
        mockMvc.perform(authed(post("/v1/platform/tenants/{id}/suspend", TENANT_ID), "platform:tenant:reactivate"))
                .andExpect(status().isForbidden());
        mockMvc.perform(authed(post("/v1/platform/tenants/{id}/reactivate", TENANT_ID), "platform:tenant:reactivate"))
                .andExpect(status().isOk());
        mockMvc.perform(authed(
                        post("/v1/platform/tenants/{id}/decommission", TENANT_ID), "platform:tenant:decommission"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DECOMMISSIONED"));
        mockMvc.perform(authed(post("/v1/platform/tenants/{id}/decommission", TENANT_ID), "platform:tenant:suspend"))
                .andExpect(status().isForbidden());
    }

    @Test
    void illegalTransitionIs409() throws Exception {
        when(tenantService.suspend(TENANT_ID))
                .thenThrow(
                        new InvalidStatusTransitionException(TENANT_ID, TenantStatus.PENDING, TenantStatus.SUSPENDED));
        mockMvc.perform(authed(post("/v1/platform/tenants/{id}/suspend", TENANT_ID), "platform:tenant:suspend"))
                .andExpect(status().isConflict());
    }

    @Test
    void updateTenant() throws Exception {
        when(tenantService.update(eq(TENANT_ID), any())).thenReturn(tenant(TenantStatus.ACTIVE));
        mockMvc.perform(authed(
                        patch("/v1/platform/tenants/{id}", TENANT_ID)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"displayName\":\"Acme Tire\"}"),
                        "platform:tenant:update"))
                .andExpect(status().isOk());
    }

    @Test
    void accountsCrud() throws Exception {
        AccountResponse account = AccountResponse.builder()
                .id(ACCOUNT_ID)
                .legalName("Acme LLC")
                .homeCountry("US")
                .homeCurrency("USD")
                .contacts(List.of())
                .tenantIds(List.of())
                .build();
        when(accountService.create(any())).thenReturn(account);
        when(accountService.list()).thenReturn(List.of(account));
        when(accountService.get(ACCOUNT_ID)).thenReturn(account);
        when(accountService.update(eq(ACCOUNT_ID), any())).thenReturn(account);

        String body = "{\"legalName\":\"Acme LLC\",\"homeCountry\":\"US\",\"homeCurrency\":\"USD\"}";
        mockMvc.perform(authed(
                        post("/v1/platform/accounts")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body),
                        "platform:account:create"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.legalName").value("Acme LLC"));
        mockMvc.perform(authed(
                        post("/v1/platform/accounts")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"legalName\":\"\",\"homeCountry\":\"usa\",\"homeCurrency\":\"USD\"}"),
                        "platform:account:create"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(authed(get("/v1/platform/accounts"), "platform:account:read"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(ACCOUNT_ID.toString()));
        mockMvc.perform(authed(get("/v1/platform/accounts/{id}", ACCOUNT_ID), "platform:tenant:read"))
                .andExpect(status().isForbidden());
        mockMvc.perform(authed(
                        patch("/v1/platform/accounts/{id}", ACCOUNT_ID)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"tradingName\":\"Acme\"}"),
                        "platform:account:update"))
                .andExpect(status().isOk());
    }

    @Test
    void contactsAndBillingProfile() throws Exception {
        UUID contactId = UUID.randomUUID();
        AccountContactResponse contact = AccountContactResponse.builder()
                .id(contactId)
                .accountId(ACCOUNT_ID)
                .name("Jordan")
                .role(ContactRole.OWNER)
                .email("j@acme.example")
                .build();
        when(accountService.addContact(eq(ACCOUNT_ID), any())).thenReturn(contact);
        when(accountService.updateContact(eq(ACCOUNT_ID), eq(contactId), any())).thenReturn(contact);
        when(accountService.putBillingProfile(eq(ACCOUNT_ID), any()))
                .thenReturn(BillingProfileResponse.builder()
                        .accountId(ACCOUNT_ID)
                        .paymentProcessorCustomerTokenPresent(true)
                        .build());

        String contactBody = "{\"name\":\"Jordan\",\"role\":\"OWNER\",\"email\":\"j@acme.example\"}";
        mockMvc.perform(authed(
                        post("/v1/platform/accounts/{id}/contacts", ACCOUNT_ID)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(contactBody),
                        "platform:account:update"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("OWNER"));
        mockMvc.perform(authed(
                        put("/v1/platform/accounts/{id}/contacts/{contactId}", ACCOUNT_ID, contactId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(contactBody),
                        "platform:account:update"))
                .andExpect(status().isOk());
        mockMvc.perform(authed(
                        delete("/v1/platform/accounts/{id}/contacts/{contactId}", ACCOUNT_ID, contactId),
                        "platform:account:update"))
                .andExpect(status().isNoContent());
        mockMvc.perform(authed(
                        delete("/v1/platform/accounts/{id}/contacts/{contactId}", ACCOUNT_ID, contactId),
                        "platform:account:read"))
                .andExpect(status().isForbidden());

        String billingBody = """
                {"addressLine1":"100 Main St","city":"Springfield","country":"US","paymentTerms":"NET30",
                 "invoicingEmail":"billing@acme.example","paymentProcessorCustomerToken":"cus_123"}
                """;
        mockMvc.perform(authed(
                        put("/v1/platform/accounts/{id}/billing-profile", ACCOUNT_ID)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(billingBody),
                        "platform:account:update"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentProcessorCustomerTokenPresent").value(true))
                .andExpect(jsonPath("$.paymentProcessorCustomerToken").doesNotExist());
    }
}
