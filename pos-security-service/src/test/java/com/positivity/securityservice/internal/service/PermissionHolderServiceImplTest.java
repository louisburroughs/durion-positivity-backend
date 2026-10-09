package com.positivity.securityservice.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.securityservice.internal.dto.PermissionHolderRole;
import com.positivity.securityservice.internal.dto.PermissionHolderRow;
import com.positivity.securityservice.internal.dto.PermissionHolders;
import com.positivity.securityservice.internal.dto.PermissionHoldersResponse;
import com.positivity.securityservice.internal.enums.LocationScope;
import com.positivity.securityservice.internal.exception.PermissionHolderQueryInvalidException;
import com.positivity.securityservice.internal.exception.PermissionHolderScopeDeniedException;
import com.positivity.securityservice.internal.exception.PermissionNotRegisteredException;
import com.positivity.securityservice.internal.repository.PermissionRepository;
import com.positivity.securityservice.internal.repository.RoleRepository;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * #2669: normalisation, ordering, grouping, the D2 scope check, the 422 for an unregistered code and
 * the empty-holders answer of {@link PermissionHolderServiceImpl}. The Postgres/RLS behaviour is in
 * {@code PermissionHoldersIT}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PermissionHolderServiceImpl (#2669)")
class PermissionHolderServiceImplTest {

    private static final String APPROVE = "accounting:ap:approve";
    private static final String OVER_LIMIT = "accounting:ap:approve_over_limit";
    private static final String REJECT = "accounting:ap:reject";
    private static final String PAY = "accounting:ap:pay";
    private static final String POLICY = "accounting:ap_approval_policy:manage";
    private static final String TIME_ENTRY_APPROVE = "people:timeEntry:approve";
    private static final List<String> AP_CODES = List.of(APPROVE, OVER_LIMIT, REJECT, PAY, POLICY);

    private static final Set<String> ROLE_VIEWER = Set.of("security:role:view");
    private static final Set<String> POLICY_MANAGER = Set.of(POLICY);

    @Mock
    private RoleRepository roleRepository;

    @Mock
    private PermissionRepository permissionRepository;

    private PermissionHolderServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new PermissionHolderServiceImpl(roleRepository, permissionRepository);
    }

    private void allRegistered() {
        when(permissionRepository.findNamesIgnoreCase(anyCollection()))
                .thenAnswer(inv -> List.copyOf(inv.<java.util.Collection<String>>getArgument(0)));
    }

    private static PermissionHolderRow row(String permission, String role, String templateKey, LocationScope scope) {
        return new PermissionHolderRow(permission, role, templateKey, scope);
    }

    @Nested
    @DisplayName("answer")
    class Answer {

        @Test
        @DisplayName("groups rows per code, in request order, roles sorted by name ignoring case, empty when unheld")
        void groupsPerCodeInRequestOrderWithRolesSortedByName() {
            allRegistered();
            when(roleRepository.findHolderRowsByPermissionNames(List.of(PAY, APPROVE, OVER_LIMIT)))
                    .thenReturn(List.of(
                            row(APPROVE, "GENERAL_MANAGER", "GENERAL_MANAGER", LocationScope.ALL),
                            row(APPROVE, "accounting_lead", null, LocationScope.LOCATION),
                            row(APPROVE, "ADMIN", "ADMIN", LocationScope.ALL),
                            row(PAY, "CONTROLLER", "CONTROLLER", LocationScope.ALL)));

            PermissionHoldersResponse response =
                    service.listPermissionHolders(List.of(PAY, APPROVE, OVER_LIMIT), ROLE_VIEWER);

            assertThat(response.permissions())
                    .extracting(PermissionHolders::permission)
                    .containsExactly(PAY, APPROVE, OVER_LIMIT);
            assertThat(response.permissions().get(0).roles())
                    .containsExactly(new PermissionHolderRole("CONTROLLER", "CONTROLLER", LocationScope.ALL));
            assertThat(response.permissions().get(1).roles())
                    .extracting(PermissionHolderRole::name)
                    .containsExactly("accounting_lead", "ADMIN", "GENERAL_MANAGER");
            assertThat(response.permissions().get(1).roles().get(0))
                    .isEqualTo(new PermissionHolderRole("accounting_lead", null, LocationScope.LOCATION));
            assertThat(response.permissions().get(2).roles())
                    .as("a registered code nobody holds is an answer, not an error")
                    .isEmpty();
        }

        @Test
        @DisplayName("a camelCase catalog code is matched ignoring case and answered in the catalog's spelling")
        void aCamelCaseCodeIsAnsweredInTheCatalogsSpelling() {
            when(permissionRepository.findNamesIgnoreCase(List.of("people:timeentry:approve")))
                    .thenReturn(List.of(TIME_ENTRY_APPROVE));
            when(roleRepository.findHolderRowsByPermissionNames(List.of(TIME_ENTRY_APPROVE)))
                    .thenReturn(
                            List.of(row(TIME_ENTRY_APPROVE, "SHOP_MANAGER", "SHOP_MANAGER", LocationScope.LOCATION)));

            for (String asked : List.of(TIME_ENTRY_APPROVE, "PEOPLE:TIMEENTRY:APPROVE", "people:timeentry:approve")) {
                PermissionHoldersResponse response = service.listPermissionHolders(List.of(asked), ROLE_VIEWER);
                assertThat(response.permissions())
                        .as("asked as %s", asked)
                        .containsExactly(new PermissionHolders(
                                TIME_ENTRY_APPROVE,
                                List.of(new PermissionHolderRole(
                                        "SHOP_MANAGER", "SHOP_MANAGER", LocationScope.LOCATION))));
            }
        }

        @Test
        @DisplayName("trims, matches ignoring case and de-duplicates in first-seen order")
        void normalisesAndDeduplicatesInFirstSeenOrder() {
            allRegistered();
            when(roleRepository.findHolderRowsByPermissionNames(List.of(APPROVE, PAY)))
                    .thenReturn(List.of());

            PermissionHoldersResponse response = service.listPermissionHolders(
                    List.of(" accounting:AP:approve ", PAY, "ACCOUNTING:ap:APPROVE", PAY), ROLE_VIEWER);

            assertThat(response.permissions())
                    .extracting(PermissionHolders::permission)
                    .containsExactly(APPROVE, PAY);
        }
    }

    @Nested
    @DisplayName("shape (400)")
    class Shape {

        @Test
        @DisplayName("no code is refused")
        void noCodeIsRefused() {
            assertThatThrownBy(() -> service.listPermissionHolders(List.of(), ROLE_VIEWER))
                    .isInstanceOf(PermissionHolderQueryInvalidException.class)
                    .satisfies(ex -> assertThat(((PermissionHolderQueryInvalidException) ex).fieldMessages())
                            .containsExactly("at least one permission code is required"));
            verifyNoInteractions(roleRepository, permissionRepository);
        }

        @Test
        @DisplayName("21 distinct codes are refused; 20 are accepted")
        void moreThanTwentyDistinctCodesAreRefused() {
            List<String> twentyOne = IntStream.rangeClosed(1, 21)
                    .mapToObj(i -> "accounting:ap:action" + i)
                    .toList();
            assertThatThrownBy(() -> service.listPermissionHolders(twentyOne, ROLE_VIEWER))
                    .isInstanceOf(PermissionHolderQueryInvalidException.class)
                    .satisfies(ex -> assertThat(((PermissionHolderQueryInvalidException) ex).fieldMessages())
                            .singleElement()
                            .asString()
                            .contains("at most 20", "21 were sent"));
            verifyNoInteractions(roleRepository, permissionRepository);

            allRegistered();
            when(roleRepository.findHolderRowsByPermissionNames(twentyOne.subList(0, 20)))
                    .thenReturn(List.of());
            assertThat(service.listPermissionHolders(twentyOne.subList(0, 20), ROLE_VIEWER)
                            .permissions())
                    .hasSize(20);
        }

        @Test
        @DisplayName("a code that is not domain:resource:action is refused, naming every bad value")
        void malformedCodesAreRefusedNamingEach() {
            assertThatThrownBy(() -> service.listPermissionHolders(
                            List.of("accounting::approve", APPROVE, "accounting:ap", "9x:ap:approve"), ROLE_VIEWER))
                    .isInstanceOf(PermissionHolderQueryInvalidException.class)
                    .satisfies(ex -> assertThat(((PermissionHolderQueryInvalidException) ex).fieldMessages())
                            .containsExactly(
                                    "'accounting::approve' is not a domain:resource:action permission code",
                                    "'accounting:ap' is not a domain:resource:action permission code",
                                    "'9x:ap:approve' is not a domain:resource:action permission code"));
            verifyNoInteractions(roleRepository, permissionRepository);
        }

        @Test
        @DisplayName("a code longer than 255 characters is refused")
        void anOverlongCodeIsRefused() {
            String overlong = "accounting:ap:" + "a".repeat(242);
            assertThat(overlong).hasSize(256);
            assertThatThrownBy(() -> service.listPermissionHolders(List.of(overlong), ROLE_VIEWER))
                    .isInstanceOf(PermissionHolderQueryInvalidException.class);
        }

        @Test
        @DisplayName("shape is checked before scope: a scoped caller sending a malformed code gets 400")
        void shapeIsCheckedBeforeScope() {
            assertThatThrownBy(() -> service.listPermissionHolders(List.of("security::edit"), POLICY_MANAGER))
                    .isInstanceOf(PermissionHolderQueryInvalidException.class);
        }
    }

    @Nested
    @DisplayName("scope (403, D2)")
    class Scope {

        @Test
        @DisplayName("a policy manager may ask about the five AP codes")
        void aPolicyManagerMayAskAboutTheFiveApCodes() {
            allRegistered();
            when(roleRepository.findHolderRowsByPermissionNames(AP_CODES)).thenReturn(List.of());

            assertThat(service.listPermissionHolders(AP_CODES, POLICY_MANAGER).permissions())
                    .extracting(PermissionHolders::permission)
                    .containsExactlyElementsOf(AP_CODES);
        }

        @Test
        @DisplayName(
                "the scope is compared ignoring case: ACCOUNTING:AP:PAY is in scope and answered as accounting:ap:pay")
        void theScopeIsComparedIgnoringCase() {
            allRegistered();
            when(roleRepository.findHolderRowsByPermissionNames(List.of(PAY))).thenReturn(List.of());

            assertThat(service.listPermissionHolders(List.of("ACCOUNTING:AP:PAY"), POLICY_MANAGER)
                            .permissions())
                    .extracting(PermissionHolders::permission)
                    .containsExactly(PAY);
        }

        @Test
        @DisplayName("a policy manager asking about security:role:edit is refused, naming it, and nothing is read")
        void aPolicyManagerOutsideItsScopeIsRefusedBeforeAnyRead() {
            assertThatThrownBy(
                            () -> service.listPermissionHolders(List.of(APPROVE, "security:role:edit"), POLICY_MANAGER))
                    .isInstanceOf(PermissionHolderScopeDeniedException.class)
                    .hasMessageContaining("security:role:edit")
                    .satisfies(ex -> assertThat(((PermissionHolderScopeDeniedException) ex).outOfScope())
                            .containsExactly("security:role:edit"));
            verifyNoInteractions(roleRepository, permissionRepository);
        }

        @Test
        @DisplayName("scope is checked before registration: an unregistered out-of-scope code is 403, not 422")
        void scopeIsCheckedBeforeRegistration() {
            assertThatThrownBy(
                            () -> service.listPermissionHolders(List.of("security:role:nonexistent"), POLICY_MANAGER))
                    .isInstanceOf(PermissionHolderScopeDeniedException.class);
            verifyNoInteractions(roleRepository, permissionRepository);
        }

        @Test
        @DisplayName("a caller holding neither authority is refused every code (deny by default)")
        void aCallerWithNeitherAuthorityIsRefused() {
            assertThatThrownBy(() -> service.listPermissionHolders(List.of(APPROVE), Set.of(APPROVE)))
                    .isInstanceOf(PermissionHolderScopeDeniedException.class);
            verifyNoInteractions(roleRepository, permissionRepository);
        }

        @Test
        @DisplayName("security:role:view may ask about any registered code")
        void aRoleViewerMayAskAboutAnyCode() {
            allRegistered();
            when(roleRepository.findHolderRowsByPermissionNames(List.of("security:role:edit")))
                    .thenReturn(List.of(row("security:role:edit", "ADMIN", "ADMIN", LocationScope.ALL)));

            assertThat(service.listPermissionHolders(List.of("security:role:edit"), ROLE_VIEWER)
                            .permissions()
                            .get(0)
                            .roles())
                    .extracting(PermissionHolderRole::name)
                    .containsExactly("ADMIN");
        }
    }

    @Nested
    @DisplayName("registration (422)")
    class Registration {

        @Test
        @DisplayName("an unregistered code is refused, not answered empty, and no holder is read")
        void anUnregisteredCodeIsRefusedNotAnsweredEmpty() {
            when(permissionRepository.findNamesIgnoreCase(List.of(APPROVE, "accounting:ap:aprove")))
                    .thenReturn(List.of(APPROVE));

            assertThatThrownBy(
                            () -> service.listPermissionHolders(List.of(APPROVE, "accounting:ap:aprove"), ROLE_VIEWER))
                    .isInstanceOf(PermissionNotRegisteredException.class)
                    .satisfies(ex -> assertThat(((PermissionNotRegisteredException) ex).unregistered())
                            .containsExactly("accounting:ap:aprove"));
            verify(permissionRepository).findNamesIgnoreCase(List.of(APPROVE, "accounting:ap:aprove"));
            verifyNoInteractions(roleRepository);
        }
    }
}
