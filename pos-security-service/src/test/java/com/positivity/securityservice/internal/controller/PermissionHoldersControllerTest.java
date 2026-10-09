package com.positivity.securityservice.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.securityservice.internal.dto.PermissionHolderRow;
import com.positivity.securityservice.internal.dto.RoleDto;
import com.positivity.securityservice.internal.enums.LocationScope;
import com.positivity.securityservice.internal.repository.PermissionRepository;
import com.positivity.securityservice.internal.repository.RoleRepository;
import com.positivity.securityservice.internal.security.JwtAuthenticationFilter;
import com.positivity.securityservice.internal.service.CustomUserDetailsService;
import com.positivity.securityservice.internal.service.PermissionHolderServiceImpl;
import com.positivity.securityservice.internal.service.RoleAuthorityService;
import com.positivity.securityservice.internal.service.RoleManagementService;
import com.positivity.securityservice.internal.service.RolePermissionService;
import jakarta.servlet.FilterChain;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * #2669 controller slice for {@code GET /v1/roles/permission-holders}: the gate for each authority
 * shape, the 400 shapes, the route against {@code GET /v1/roles/{id}} and the {@code ApiError}
 * bodies. The real {@link PermissionHolderServiceImpl} runs over mocked repositories, so the D2 scope
 * check is exercised through the authorities the request actually carries; callers are built from
 * authorities, never from role names. A request with no token is answered 401 by the gateway and the
 * token filter, which this slice mocks, so it is not asserted here.
 */
@WebMvcTest(RoleController.class)
@Import(PermissionHolderServiceImpl.class)
@DisplayName("GET /v1/roles/permission-holders (#2669)")
class PermissionHoldersControllerTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-10-09T00:00:00Z"), ZoneOffset.UTC);
    private static final String PATH = "/v1/roles/permission-holders";
    private static final List<String> AP_CODES = List.of(
            "accounting:ap:approve",
            "accounting:ap:approve_over_limit",
            "accounting:ap:reject",
            "accounting:ap:pay",
            "accounting:ap_approval_policy:manage");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RoleRepository roleRepository;

    @MockitoBean
    private PermissionRepository permissionRepository;

    @MockitoBean
    private RoleManagementService roleManagementService;

    @MockitoBean
    private RolePermissionService rolePermissionService;

    @MockitoBean
    private RoleAuthorityService roleAuthorityService;

    @MockitoBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @MockitoBean
    private CustomUserDetailsService customUserDetailsService;

    @BeforeEach
    void setUp() throws Exception {
        doAnswer(inv -> {
                    ((FilterChain) inv.getArgument(2)).doFilter(inv.getArgument(0), inv.getArgument(1));
                    return null;
                })
                .when(jwtAuthenticationFilter)
                .doFilter(any(), any(), any());
    }

    private void allRegistered() {
        when(permissionRepository.findNamesIgnoreCase(anyCollection()))
                .thenAnswer(inv -> List.copyOf(inv.<java.util.Collection<String>>getArgument(0)));
    }

    private static MockHttpServletRequestBuilder holders(List<String> codes) {
        MockHttpServletRequestBuilder request = get(PATH);
        codes.forEach(code -> request.queryParam("permission", code));
        return request;
    }

    @Test
    @WithMockUser(authorities = "security:role:view")
    @DisplayName("security:role:view: 200 with every code in request order; only the documented keys appear")
    void aRoleViewerGetsTheAnswerWithOnlyRoleFields() throws Exception {
        allRegistered();
        when(roleRepository.findHolderRowsByPermissionNames(AP_CODES))
                .thenReturn(List.of(
                        new PermissionHolderRow("accounting:ap:approve", "CONTROLLER", "CONTROLLER", LocationScope.ALL),
                        new PermissionHolderRow("accounting:ap:approve", "ADMIN", "ADMIN", LocationScope.ALL),
                        new PermissionHolderRow(
                                "accounting:ap:approve_over_limit", "Night Supervisor", null, LocationScope.LOCATION)));

        String body = mockMvc.perform(holders(AP_CODES))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions.length()").value(5))
                .andExpect(jsonPath("$.permissions[0].permission").value("accounting:ap:approve"))
                .andExpect(jsonPath("$.permissions[0].roles[0].name").value("ADMIN"))
                .andExpect(jsonPath("$.permissions[0].roles[0].templateKey").value("ADMIN"))
                .andExpect(jsonPath("$.permissions[0].roles[0].locationScope").value("ALL"))
                .andExpect(jsonPath("$.permissions[0].roles[1].name").value("CONTROLLER"))
                .andExpect(jsonPath("$.permissions[1].roles[0].name").value("Night Supervisor"))
                .andExpect(jsonPath("$.permissions[1].roles[0].locationScope").value("LOCATION"))
                .andExpect(jsonPath("$.permissions[3].permission").value("accounting:ap:pay"))
                .andExpect(jsonPath("$.permissions[3].roles").isEmpty())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).as("a custom role's templateKey is an explicit null").contains("\"templateKey\":null");
        // AC 10: no user ids, names or counts — no key beyond the six of the contract.
        assertThat(allKeys(new ObjectMapper().readTree(body)))
                .containsExactlyInAnyOrder(
                        "permissions", "permission", "roles", "name", "templateKey", "locationScope");
    }

    @Test
    @WithMockUser(authorities = "accounting:ap_approval_policy:manage")
    @DisplayName("accounting:ap_approval_policy:manage only: the five AP codes → 200")
    void aPolicyManagerGetsTheFiveApCodes() throws Exception {
        allRegistered();
        when(roleRepository.findHolderRowsByPermissionNames(AP_CODES)).thenReturn(List.of());

        mockMvc.perform(holders(AP_CODES))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions.length()").value(5));
    }

    @Test
    @WithMockUser(authorities = "accounting:ap_approval_policy:manage")
    @DisplayName("policy manager asking about security:role:edit → 403 PERMISSION_HOLDER_SCOPE_DENIED, nothing read")
    void aPolicyManagerOutsideItsScopeIsDenied() throws Exception {
        mockMvc.perform(holders(List.of("accounting:ap:approve", "security:role:edit")))
                .andExpect(status().isForbidden())
                .andExpect(header().exists("X-Correlation-Id"))
                .andExpect(jsonPath("$.code").value("PERMISSION_HOLDER_SCOPE_DENIED"))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("security:role:edit")))
                .andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.not(
                                org.hamcrest.Matchers.containsString("accounting:ap:approve"))));
        verifyNoInteractions(roleRepository, permissionRepository);
    }

    @Test
    @WithMockUser(authorities = {"accounting:ap:approve", "accounting:ap:view"})
    @DisplayName("the clerk shape (neither authority) → 403 with the module's access-denied code")
    void aCallerWithNeitherAuthorityIsForbidden() throws Exception {
        mockMvc.perform(holders(List.of("accounting:ap:approve")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        verifyNoInteractions(roleRepository, permissionRepository);
    }

    @Test
    @WithMockUser(authorities = "security:role:view")
    @DisplayName("no permission parameter → 400 VALIDATION_ERROR on permission")
    void noPermissionIsABadRequest() throws Exception {
        mockMvc.perform(get(PATH))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("permission"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value("at least one permission code is required"));
    }

    @Test
    @WithMockUser(authorities = "security:role:view")
    @DisplayName("21 distinct codes → 400 VALIDATION_ERROR")
    void twentyOneCodesIsABadRequest() throws Exception {
        List<String> codes = IntStream.rangeClosed(1, 21)
                .mapToObj(i -> "accounting:ap:action" + i)
                .toList();
        mockMvc.perform(holders(codes))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("permission"));
        verifyNoInteractions(roleRepository, permissionRepository);
    }

    @Test
    @WithMockUser(authorities = "security:role:view")
    @DisplayName("accounting::approve → 400 VALIDATION_ERROR naming the value")
    void aMalformedCodeIsABadRequestNamingIt() throws Exception {
        mockMvc.perform(holders(List.of("accounting::approve")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("permission"))
                .andExpect(jsonPath("$.fieldErrors[0].message")
                        .value(org.hamcrest.Matchers.containsString("accounting::approve")));
    }

    @Test
    @WithMockUser(authorities = "security:role:view")
    @DisplayName("accounting:AP:approve is accepted and normalised; duplicates answered once in first-seen order")
    void upperCaseIsNormalisedAndDuplicatesCollapse() throws Exception {
        allRegistered();
        when(roleRepository.findHolderRowsByPermissionNames(List.of("accounting:ap:pay", "accounting:ap:approve")))
                .thenReturn(List.of());

        mockMvc.perform(holders(List.of("accounting:ap:pay", "accounting:AP:approve", "accounting:ap:pay")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions.length()").value(2))
                .andExpect(jsonPath("$.permissions[0].permission").value("accounting:ap:pay"))
                .andExpect(jsonPath("$.permissions[1].permission").value("accounting:ap:approve"));
    }

    @Test
    @WithMockUser(authorities = "security:role:view")
    @DisplayName("a camelCase catalog code → 200, answered in the catalog's spelling")
    void aCamelCaseCodeIsAnswered() throws Exception {
        when(permissionRepository.findNamesIgnoreCase(List.of("people:timeentry:approve")))
                .thenReturn(List.of("people:timeEntry:approve"));
        when(roleRepository.findHolderRowsByPermissionNames(List.of("people:timeEntry:approve")))
                .thenReturn(List.of(new PermissionHolderRow(
                        "people:timeEntry:approve", "SHOP_MANAGER", "SHOP_MANAGER", LocationScope.LOCATION)));

        mockMvc.perform(holders(List.of("people:timeEntry:approve")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions[0].permission").value("people:timeEntry:approve"))
                .andExpect(jsonPath("$.permissions[0].roles[0].name").value("SHOP_MANAGER"));
        mockMvc.perform(holders(List.of("PEOPLE:TIMEENTRY:APPROVE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions[0].permission").value("people:timeEntry:approve"));
    }

    @Test
    @WithMockUser(authorities = "security:role:view")
    @DisplayName("an unregistered code → 422 PERMISSION_NOT_REGISTERED naming it")
    void anUnregisteredCodeIsUnprocessable() throws Exception {
        when(permissionRepository.findNamesIgnoreCase(anyCollection())).thenReturn(List.of());

        mockMvc.perform(holders(List.of("accounting:ap:aprove")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(header().exists("X-Correlation-Id"))
                .andExpect(jsonPath("$.code").value("PERMISSION_NOT_REGISTERED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("permission"))
                .andExpect(jsonPath("$.fieldErrors[0].message")
                        .value(org.hamcrest.Matchers.containsString("accounting:ap:aprove")));
        verifyNoInteractions(roleRepository);
    }

    @Test
    @WithMockUser(authorities = "security:role:view")
    @DisplayName("route: /permission-holders reaches the new handler, not getRoleById; /{uuid} is unchanged")
    void theLiteralPathWinsOverTheIdPattern() throws Exception {
        allRegistered();
        when(roleRepository.findHolderRowsByPermissionNames(List.of("accounting:ap:approve")))
                .thenReturn(List.of());

        mockMvc.perform(holders(List.of("accounting:ap:approve")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions[0].permission").value("accounting:ap:approve"));
        verify(roleManagementService, never()).getRoleById(any());

        UUID id = UUID.fromString("00000000-0000-7000-8000-000000000001");
        when(roleManagementService.getRoleById(id))
                .thenReturn(Optional.of(RoleDto.builder().id(id).name("ADMIN").build()));
        mockMvc.perform(get("/v1/roles/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("ADMIN"));
    }

    private static Set<String> allKeys(JsonNode node) {
        Set<String> keys = new HashSet<>();
        collectKeys(node, keys);
        return keys;
    }

    private static void collectKeys(JsonNode node, Set<String> keys) {
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.properties().iterator();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                keys.add(field.getKey());
                collectKeys(field.getValue(), keys);
            }
        } else if (node.isArray()) {
            node.forEach(child -> collectKeys(child, keys));
        }
    }

    @TestConfiguration
    @EnableMethodSecurity(prePostEnabled = true)
    static class SliceTestConfig {

        @Bean
        Clock clock() {
            return TEST_CLOCK;
        }
    }
}
