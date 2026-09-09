package com.positivity.peoplecontact.internal.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.positivity.peoplecontact.internal.client.dto.UserRoleAssignmentRequest;
import com.positivity.peoplecontact.internal.client.dto.UserRoleDto;
import com.positivity.peoplecontact.internal.exception.PeopleContactValidationException;
import com.positivity.peoplecontact.internal.exception.SecurityServiceContractException;
import jakarta.persistence.EntityNotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Unit tests for {@link SecurityServiceClient}.
 *
 * <p>
 * This is the RBAC edge of pos-people-contact: it is how a person's login gets
 * its roles, and how those roles are taken away again. Two behaviours matter
 * more than the plumbing:
 *
 * <ul>
 * <li><b>Revocation ends an assignment rather than deleting history.</b> The
 * client looks only at assignments that have not already ended, resolves the one
 * matching the requested role, and sets an end date. An already-expired
 * assignment must not be picked, or the revoke would target the wrong row and
 * silently leave the live one in place.</li>
 * <li><b>Failures are classified, not flattened.</b> A 404 is an
 * {@link EntityNotFoundException}, a 400 a {@link PeopleContactValidationException} only when
 * the argument that provoked it is genuinely caller-supplied — a downstream 400 on a call whose
 * every value is fixed or module-internal (the username-less {@code GET /v1/users}) is a
 * {@link SecurityServiceContractException}
 * instead, because no caller action could explain it (never a bare {@link
 * IllegalArgumentException} either way — see {@link PeopleContactValidationException}'s javadoc
 * for why) — and anything 5xx a {@link SecurityServiceException} carrying the status — so a
 * caller can tell "you asked for something that does not exist" from "security service is
 * down", and only retry the second.</li>
 * </ul>
 *
 * <p>
 * A {@code null} body on a 200 is also treated as a failure rather than as an
 * empty result: silently returning "no roles" when the service answered
 * unintelligibly would read as a valid, role-less user.
 *
 * <p>
 * The third theme is the wire contract itself. Under ADR-0061 (issue #1875) a role assignment is
 * an effective-dated user-to-role link and nothing more, and pos-security-service answers with a
 * flat {@code userId}/{@code roleId} shape over a {@code LocalDateTime} window. Every one of
 * those three facts used to be wrong here and none of them failed loudly — Spring drops unknown
 * query parameters, Jackson ignores unknown body fields, and a mis-declared nested object simply
 * deserializes to null — so the assertions below pin the request body, the call count, and the
 * response shape rather than trusting compilation.
 */
@DisplayName("SecurityServiceClient — RBAC lookups and assignment lifecycle")
class SecurityServiceClientTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000e1");
    private static final UUID ROLE_ID = UUID.fromString("00000000-0000-0000-0000-0000000000e2");
    private static final UUID ASSIGNMENT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000e3");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 11, 12, 0);

    private MockRestServiceServer server;
    private SecurityServiceClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://security");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new SecurityServiceClient(
                builder.build(), Clock.fixed(Instant.parse("2026-08-11T12:00:00Z"), ZoneOffset.UTC));
    }

    private void expectRoleLookup(String roleName) {
        server.expect(requestTo("http://security/v1/roles/by-name/" + roleName))
                .andRespond(withSuccess("""
                        {"id":"%s","name":"%s","description":"d"}""".formatted(ROLE_ID, roleName), MediaType.APPLICATION_JSON));
    }

    @Nested
    @DisplayName("getUserByUsername")
    class UserLookup {

        @Test
        @DisplayName("matches the username case-insensitively and ignores other users")
        void matchesCaseInsensitively() {
            server.expect(requestTo("http://security/v1/users"))
                    .andRespond(withSuccess("""
                            [{"id":"%s","username":"Ada.Lovelace"},
                             {"id":"%s","username":"someone.else"}]""".formatted(USER_ID, UUID.randomUUID()), MediaType.APPLICATION_JSON));

            assertThat(client.getUserByUsername("ada.lovelace"))
                    .get()
                    .extracting(u -> u.getId())
                    .isEqualTo(USER_ID);
            server.verify();
        }

        @Test
        @DisplayName("reports an unknown username as empty rather than as an error")
        void unknownUsernameIsEmpty() {
            server.expect(requestTo("http://security/v1/users"))
                    .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

            assertThat(client.getUserByUsername("nobody")).isEmpty();
            server.verify();
        }

        @Test
        @DisplayName("a downstream 400 is a contract violation, not a caller error — username is never sent")
        void a400IsAContractViolationNotCallerError() {
            // GET /v1/users carries no query parameter (filtering happens client-side below), so
            // no value this caller supplied could have provoked a 400: it must mean this call's
            // shape has drifted from what pos-security-service expects.
            server.expect(requestTo("http://security/v1/users")).andRespond(withBadRequest());
            assertThatThrownBy(() -> client.getUserByUsername("bad"))
                    .isInstanceOf(SecurityServiceContractException.class)
                    .isNotInstanceOf(PeopleContactValidationException.class)
                    .isNotInstanceOf(SecurityServiceException.class);
            server.verify();
        }

        @Test
        @DisplayName("classifies 401/403 and 5xx so callers can react correctly")
        void failureClassification() {
            server.expect(requestTo("http://security/v1/users")).andRespond(withStatus(HttpStatus.FORBIDDEN));
            // Not authorized is a security-service condition, not a bad request from the caller.
            assertThatThrownBy(() -> client.getUserByUsername("ada")).isInstanceOf(SecurityServiceException.class);

            setUp();
            server.expect(requestTo("http://security/v1/users")).andRespond(withServerError());
            assertThatThrownBy(() -> client.getUserByUsername("ada")).isInstanceOf(SecurityServiceException.class);
            server.verify();
        }

        @Test
        @DisplayName("treats a null body on a 200 as a failure, not as an empty user list")
        void nullBodyIsAFailure() {
            server.expect(requestTo("http://security/v1/users"))
                    .andRespond(withSuccess("null", MediaType.APPLICATION_JSON));

            // Returning empty here would read as "this user genuinely does not exist".
            assertThatThrownBy(() -> client.getUserByUsername("ada")).isInstanceOf(IllegalStateException.class);
            server.verify();
        }
    }

    @Nested
    @DisplayName("getAvailableRoles / getUserRoleAssignments")
    class Reads {

        @Test
        @DisplayName("asks for the whole catalog with no query parameter, since GET /v1/roles takes none")
        void rolesCatalogIsUnfiltered() {
            // requestTo() matches the full URI including its query string, so a stray
            // ?scopeType=... would fail this expectation rather than being silently dropped by
            // Spring the way pos-security-service drops it.
            server.expect(requestTo("http://security/v1/roles"))
                    .andExpect(method(HttpMethod.GET))
                    .andRespond(withSuccess("""
                            [{"id":"%s","name":"SHOP_MGR","description":"Shop manager",
                              "permissions":[{"key":"security:role:view"}],"personaTitle":"shop manager"}]""".formatted(ROLE_ID), MediaType.APPLICATION_JSON));

            assertThat(client.getAvailableRoles()).singleElement().satisfies(role -> {
                assertThat(role.getId()).isEqualTo(ROLE_ID);
                assertThat(role.getName()).isEqualTo("SHOP_MGR");
                // The name IS the stable code: it is what by-name role resolution and every
                // downstream caller selecting a role expect to receive.
                assertThat(role.getCode()).isEqualTo("SHOP_MGR");
                assertThat(role.getDescription()).isEqualTo("Shop manager");
            });
            server.verify();
        }

        @Test
        @DisplayName("ignores the permission graph and persona metadata the catalog also carries")
        void rolesToleratesTheFullDownstreamShape() {
            // pos-security-service's RoleDto returns far more than this module reads; an
            // unknown-property failure here would take out the whole role picker.
            server.expect(requestTo("http://security/v1/roles"))
                    .andRespond(withSuccess("""
                            [{"id":"%s","name":"TECHNICIAN","description":"d","permissions":[],
                              "personaTitle":"technician","personaFocus":"f","personaTone":"t",
                              "mcpPersonaRank":35,"mcpPersonaEligible":true,
                              "createdAt":"2026-01-15T09:30:00Z","createdBy":"system",
                              "lastModifiedAt":"2026-01-16T11:00:00Z","lastModifiedBy":"jane.doe"}]""".formatted(ROLE_ID), MediaType.APPLICATION_JSON));

            assertThat(client.getAvailableRoles())
                    .singleElement()
                    .extracting(r -> r.getCode())
                    .isEqualTo("TECHNICIAN");
            server.verify();
        }

        @Test
        @DisplayName("maps a missing roles endpoint to not-found")
        void rolesFailureClassification() {
            server.expect(requestTo("http://security/v1/roles")).andRespond(withResourceNotFound());
            assertThatThrownBy(() -> client.getAvailableRoles()).isInstanceOf(EntityNotFoundException.class);
            server.verify();
        }

        @Test
        @DisplayName("treats a null catalog body as a failure rather than as an empty role catalog")
        void nullRolesBodyIsAFailure() {
            server.expect(requestTo("http://security/v1/roles"))
                    .andRespond(withSuccess("null", MediaType.APPLICATION_JSON));

            assertThatThrownBy(() -> client.getAvailableRoles()).isInstanceOf(IllegalStateException.class);
            server.verify();
        }

        @Test
        @DisplayName("sends includeHistory and nothing else, the only filter the endpoint declares")
        void assignmentsQuery() {
            // requestTo() matches the full URI including its query string. This client used to
            // append ?endDate=..., which RoleController never declared and Spring therefore
            // dropped; a stray parameter fails here rather than being silently ignored.
            server.expect(requestTo(
                            "http://security/v1/roles/assignments/user/%s?includeHistory=true".formatted(USER_ID)))
                    .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

            assertThat(client.getUserRoleAssignments(USER_ID, true)).isEmpty();
            server.verify();
        }

        @Test
        @DisplayName("maps every field of the assignment response, not just the userId")
        void assignmentsAreMappedFromTheResponseShape() {
            // The response is RoleAssignmentDto. Read straight into UserRoleDto — which is what
            // this did — only userId lines up by name, so roleCode, the effective window and the
            // active flag all came back null and the caller's listing was unusable (issue #1886).
            server.expect(requestTo(
                            "http://security/v1/roles/assignments/user/%s?includeHistory=false".formatted(USER_ID)))
                    .andRespond(withSuccess(
                            "["
                                    + assignmentJson(
                                            ASSIGNMENT_ID, ROLE_ID, "SHOP_MGR", NOW.minusDays(1), NOW.plusDays(1), null)
                                    + "]",
                            MediaType.APPLICATION_JSON));

            assertThat(client.getUserRoleAssignments(USER_ID, false))
                    .singleElement()
                    .satisfies(assignment -> {
                        assertThat(assignment.getUserId()).isEqualTo(USER_ID.toString());
                        assertThat(assignment.getRoleCode()).isEqualTo("SHOP_MGR");
                        assertThat(assignment.getStartDate()).isEqualTo(NOW.minusDays(1));
                        assertThat(assignment.getEndDate()).isEqualTo(NOW.plusDays(1));
                        assertThat(assignment.getActive()).isTrue();
                    });
            server.verify();
        }

        @Test
        @DisplayName("reports an assignment whose window has closed as inactive")
        void endedAssignmentsAreNotActive() {
            server.expect(requestTo(
                            "http://security/v1/roles/assignments/user/%s?includeHistory=true".formatted(USER_ID)))
                    .andRespond(withSuccess(
                            "["
                                    + assignmentJson(
                                            ASSIGNMENT_ID,
                                            ROLE_ID,
                                            "TECHNICIAN",
                                            NOW.minusDays(10),
                                            NOW.minusDays(1),
                                            "2026-08-15T12:00:00Z")
                                    + "]",
                            MediaType.APPLICATION_JSON));

            assertThat(client.getUserRoleAssignments(USER_ID, true))
                    .singleElement()
                    .satisfies(assignment -> {
                        assertThat(assignment.getRoleCode()).isEqualTo("TECHNICIAN");
                        assertThat(assignment.getActive()).isFalse();
                    });
            server.verify();
        }

        @Test
        @DisplayName("keeps a bounded assignment active, since revokedAt only means it has an end date")
        void revokedAtDoesNotDecideTheActiveFlag() {
            // pos-security-service stamps revokedAt from its effectiveEndDate setter, so an
            // ordinary assignment created with a future end date carries one from the start.
            // Reading it as a revocation would report every bounded assignment inactive for the
            // whole of its life. The window is the test; a real revocation moves the window.
            server.expect(requestTo(
                            "http://security/v1/roles/assignments/user/%s?includeHistory=false".formatted(USER_ID)))
                    .andRespond(withSuccess(
                            "["
                                    + assignmentJson(
                                            ASSIGNMENT_ID,
                                            ROLE_ID,
                                            "TECHNICIAN",
                                            NOW.minusDays(1),
                                            NOW.plusDays(30),
                                            "2026-08-11T12:00:00Z")
                                    + "]",
                            MediaType.APPLICATION_JSON));

            assertThat(client.getUserRoleAssignments(USER_ID, false))
                    .singleElement()
                    .satisfies(assignment -> assertThat(assignment.getActive()).isTrue());
            server.verify();
        }

        @Test
        @DisplayName("leaves the role code null when a downstream older than #1886 omits it")
        void assignmentsToleratesAResponseWithoutARoleCode() {
            // A listing has no requested role code to fall back on. Null is the honest answer
            // here; inventing one would hide a version skew that the caller can see.
            server.expect(requestTo(
                            "http://security/v1/roles/assignments/user/%s?includeHistory=false".formatted(USER_ID)))
                    .andRespond(withSuccess(
                            "[" + assignmentJson(ASSIGNMENT_ID, ROLE_ID, null, NOW.minusDays(1), null, null) + "]",
                            MediaType.APPLICATION_JSON));

            assertThat(client.getUserRoleAssignments(USER_ID, false))
                    .singleElement()
                    .satisfies(assignment -> {
                        assertThat(assignment.getRoleCode()).isNull();
                        assertThat(assignment.getStartDate()).isEqualTo(NOW.minusDays(1));
                        assertThat(assignment.getActive()).isTrue();
                    });
            server.verify();
        }

        @Test
        @DisplayName("treats a null assignments body as a failure rather than as no assignments")
        void nullAssignmentsBodyIsAFailure() {
            server.expect(requestTo(org.hamcrest.Matchers.containsString("/v1/roles/assignments/user/")))
                    .andRespond(withSuccess("null", MediaType.APPLICATION_JSON));

            assertThatThrownBy(() -> client.getUserRoleAssignments(USER_ID, false))
                    .isInstanceOf(IllegalStateException.class);
            server.verify();
        }
    }

    @Nested
    @DisplayName("assignRole")
    class Assign {

        @Test
        @DisplayName("posts an effective-dated user-to-role link and nothing else — no location scope")
        void assignmentCarriesNoScope() {
            expectRoleLookup("TECH");
            server.expect(requestTo("http://security/v1/roles/assignments"))
                    .andExpect(method(HttpMethod.POST))
                    .andExpect(jsonPath("$.userId").value(USER_ID.toString()))
                    .andExpect(jsonPath("$.roleId").value(ROLE_ID.toString()))
                    // ADR-0061 (#1875) deleted both from pos-security-service. Spring drops
                    // unknown body fields, so sending them again would not fail anywhere except
                    // here — it would just quietly produce an unscoped grant while the caller
                    // believed otherwise.
                    .andExpect(jsonPath("$.scopeType").doesNotExist())
                    .andExpect(jsonPath("$.scopeLocationIds").doesNotExist())
                    .andExpect(jsonPath("$.locationId").doesNotExist())
                    .andExpect(jsonPath("$.locationIds").doesNotExist())
                    .andRespond(withSuccess(assignmentJson(NOW, null), MediaType.APPLICATION_JSON));

            UserRoleDto result = client.assignRole(UserRoleAssignmentRequest.builder()
                    .userId(USER_ID)
                    .roleCode("TECH")
                    .startDate(NOW)
                    .build());

            assertThat(result.getRoleCode()).isEqualTo("TECH");
            assertThat(result.getUserId()).isEqualTo(USER_ID.toString());
            assertThat(result.getActive()).isTrue();
            server.verify();
        }

        @Test
        @DisplayName("takes the role code from the response once the service returns one")
        void responseRoleCodeWinsOverTheRequestedOne() {
            expectRoleLookup("TECH");
            server.expect(requestTo("http://security/v1/roles/assignments"))
                    .andRespond(withSuccess(
                            assignmentJson(ASSIGNMENT_ID, ROLE_ID, "TECH", NOW, null, null),
                            MediaType.APPLICATION_JSON));

            assertThat(client.assignRole(UserRoleAssignmentRequest.builder()
                                    .userId(USER_ID)
                                    .roleCode("TECH")
                                    .startDate(NOW)
                                    .build())
                            .getRoleCode())
                    .isEqualTo("TECH");
            server.verify();
        }

        @Test
        @DisplayName("sends the effective window as a date-time, which is the only form the service accepts")
        void effectiveDatesAreSentAsDateTimes() {
            expectRoleLookup("TECH");
            // pos-security-service declares these LocalDateTime. Jackson cannot widen a date-only
            // "2026-09-01" into one, so the date-only form this client used to send came back as
            // a 400 and every dated assignment failed; only undated ones got through.
            server.expect(requestTo("http://security/v1/roles/assignments"))
                    .andExpect(jsonPath("$.effectiveStartDate").value("2026-09-01T00:00:00"))
                    .andExpect(jsonPath("$.effectiveEndDate").value("2026-12-31T23:59:59"))
                    .andRespond(withSuccess(assignmentJson(NOW, null), MediaType.APPLICATION_JSON));

            client.assignRole(UserRoleAssignmentRequest.builder()
                    .userId(USER_ID)
                    .roleCode("TECH")
                    .startDate(LocalDateTime.of(2026, 9, 1, 0, 0, 0))
                    .endDate(LocalDateTime.of(2026, 12, 31, 23, 59, 59))
                    .build());

            server.verify();
        }

        @Test
        @DisplayName("omits the effective window entirely when the caller names no dates")
        void openEndedAssignmentSendsNoDates() {
            expectRoleLookup("ADMIN");
            server.expect(requestTo("http://security/v1/roles/assignments"))
                    .andExpect(jsonPath("$.effectiveStartDate").doesNotExist())
                    .andExpect(jsonPath("$.effectiveEndDate").doesNotExist())
                    .andRespond(withSuccess(assignmentJson(null, null), MediaType.APPLICATION_JSON));

            assertThat(client.assignRole(UserRoleAssignmentRequest.builder()
                                    .userId(USER_ID)
                                    .roleCode("ADMIN")
                                    .build())
                            .getActive())
                    .isTrue();
            server.verify();
        }

        @Test
        @DisplayName("reads the user off the flat userId the response actually carries")
        void mapsTheFlatResponseShape() {
            expectRoleLookup("TECH");
            // The response is a RoleAssignmentDto: flat userId/roleId, no nested user or role
            // object. Expecting nested objects did not fail — Jackson left them null — so
            // getUser().getId() read as "assigned to nobody" on every successful assignment.
            server.expect(requestTo("http://security/v1/roles/assignments"))
                    .andRespond(
                            withSuccess("""
                            {"id":"%s","userId":"%s","roleId":"%s",
                             "effectiveStartDate":"2026-08-11T00:00:00","effectiveEndDate":"2026-12-31T00:00:00",
                             "revokedAt":null,"createdAt":"2026-08-01T00:00:00Z","createdBy":"system",
                             "lastModifiedAt":"2026-08-01T00:00:00Z","lastModifiedBy":"system"}""".formatted(ASSIGNMENT_ID, USER_ID, ROLE_ID), MediaType.APPLICATION_JSON));

            UserRoleDto result = client.assignRole(UserRoleAssignmentRequest.builder()
                    .userId(USER_ID)
                    .roleCode("TECH")
                    .build());

            assertThat(result.getUserId()).isEqualTo(USER_ID.toString());
            assertThat(result.getStartDate()).isEqualTo(LocalDateTime.of(2026, 8, 11, 0, 0));
            assertThat(result.getEndDate()).isEqualTo(LocalDateTime.of(2026, 12, 31, 0, 0));
            server.verify();
        }

        @Test
        @DisplayName("fails loudly on a response missing the userId its contract declares required")
        void missingUserIdIsADownstreamDefect() {
            expectRoleLookup("TECH");
            server.expect(requestTo("http://security/v1/roles/assignments"))
                    .andRespond(withSuccess("""
                            {"id":"%s","roleId":"%s","effectiveStartDate":"2026-08-11T00:00:00"}""".formatted(ASSIGNMENT_ID, ROLE_ID), MediaType.APPLICATION_JSON));

            // Mapping this to a null userId is how the nested-object drift stayed invisible;
            // an assignment belonging to nobody is a downstream defect, not a valid result.
            assertThatThrownBy(() -> client.assignRole(UserRoleAssignmentRequest.builder()
                            .userId(USER_ID)
                            .roleCode("TECH")
                            .build()))
                    .isInstanceOf(SecurityServiceException.class);
            server.verify();
        }

        @Test
        @DisplayName("reports an assignment that has already ended as inactive")
        void endedAssignmentIsInactive() {
            expectRoleLookup("TECH");
            server.expect(requestTo("http://security/v1/roles/assignments"))
                    .andRespond(withSuccess(
                            assignmentJson(NOW.minusDays(10), NOW.minusDays(1)), MediaType.APPLICATION_JSON));

            assertThat(client.assignRole(UserRoleAssignmentRequest.builder()
                                    .userId(USER_ID)
                                    .roleCode("TECH")
                                    .build())
                            .getActive())
                    .isFalse();
            server.verify();
        }

        @Test
        @DisplayName("reports an assignment that has not started yet as inactive")
        void futureAssignmentIsInactive() {
            expectRoleLookup("TECH");
            server.expect(requestTo("http://security/v1/roles/assignments"))
                    .andRespond(withSuccess(assignmentJson(NOW.plusDays(1), null), MediaType.APPLICATION_JSON));

            assertThat(client.assignRole(UserRoleAssignmentRequest.builder()
                                    .userId(USER_ID)
                                    .roleCode("TECH")
                                    .build())
                            .getActive())
                    .isFalse();
            server.verify();
        }

        @Test
        @DisplayName("treats the effective window as end-exclusive, to the minute")
        void windowIsEndExclusive() {
            expectRoleLookup("TECH");
            // The end is exclusive, and it is a date-time: an assignment that ended earlier today
            // is over, even though its date is still today.
            server.expect(requestTo("http://security/v1/roles/assignments"))
                    .andRespond(withSuccess(
                            assignmentJson(NOW.minusDays(1), NOW.minusMinutes(1)), MediaType.APPLICATION_JSON));

            assertThat(client.assignRole(UserRoleAssignmentRequest.builder()
                                    .userId(USER_ID)
                                    .roleCode("TECH")
                                    .build())
                            .getActive())
                    .isFalse();
            server.verify();

            setUp();
            expectRoleLookup("TECH");
            server.expect(requestTo("http://security/v1/roles/assignments"))
                    .andRespond(withSuccess(
                            assignmentJson(NOW.minusDays(1), NOW.plusMinutes(1)), MediaType.APPLICATION_JSON));

            assertThat(client.assignRole(UserRoleAssignmentRequest.builder()
                                    .userId(USER_ID)
                                    .roleCode("TECH")
                                    .build())
                            .getActive())
                    .isTrue();
            server.verify();
        }

        @Test
        @DisplayName("surfaces an unknown role name as not-found before any assignment is attempted")
        void unknownRoleIsNotFound() {
            server.expect(requestTo("http://security/v1/roles/by-name/GHOST")).andRespond(withResourceNotFound());

            assertThatThrownBy(() -> client.assignRole(UserRoleAssignmentRequest.builder()
                            .userId(USER_ID)
                            .roleCode("GHOST")
                            .build()))
                    .isInstanceOf(EntityNotFoundException.class);
            server.verify();
        }

        @Test
        @DisplayName("classifies assignment failures by status")
        void assignmentFailureClassification() {
            expectRoleLookup("TECH");
            server.expect(requestTo("http://security/v1/roles/assignments")).andRespond(withServerError());

            assertThatThrownBy(() -> client.assignRole(UserRoleAssignmentRequest.builder()
                            .userId(USER_ID)
                            .roleCode("TECH")
                            .build()))
                    .isInstanceOf(SecurityServiceException.class);
            server.verify();
        }
    }

    @Nested
    @DisplayName("revokeRole")
    class Revoke {

        @Test
        @DisplayName("ends the live assignment for that role, ignoring ones that already expired")
        void revokesTheLiveAssignment() {
            UUID expiredAssignment = UUID.randomUUID();
            expectRoleLookup("TECH");
            server.expect(requestTo(org.hamcrest.Matchers.containsString("/v1/roles/assignments/user/")))
                    .andRespond(withSuccess(
                            """
                            [%s,%s]""".formatted(
                                            assignmentJson(expiredAssignment, NOW.minusDays(30), NOW.minusDays(5)),
                                            assignmentJson(ASSIGNMENT_ID, NOW.minusDays(3), null)),
                            MediaType.APPLICATION_JSON));
            // The already-ended assignment must not be the one revoked, or the live grant survives.
            // endDate binds downstream as an ISO LocalDateTime; a date-only value does not bind.
            server.expect(requestTo("http://security/v1/roles/assignments/%s?endDate=2026-08-20T09:30:15"
                            .formatted(ASSIGNMENT_ID)))
                    .andExpect(method(HttpMethod.DELETE))
                    .andRespond(withStatus(HttpStatus.NO_CONTENT));

            client.revokeRole(USER_ID, "TECH", LocalDateTime.of(2026, 8, 20, 9, 30, 15));

            server.verify();
        }

        @Test
        @DisplayName("matches the assignment on the flat roleId the response carries")
        void matchesOnFlatRoleId() {
            UUID otherRole = UUID.fromString("00000000-0000-0000-0000-0000000000e9");
            expectRoleLookup("TECH");
            // Reading the role off a nested object that is never sent left every candidate
            // looking like "not this role"; the id has to come off roleId.
            server.expect(requestTo(org.hamcrest.Matchers.containsString("/v1/roles/assignments/user/")))
                    .andRespond(withSuccess(
                            """
                            [%s,%s]""".formatted(
                                            assignmentJson(UUID.randomUUID(), otherRole, NOW.minusDays(3), null),
                                            assignmentJson(ASSIGNMENT_ID, ROLE_ID, NOW.minusDays(3), null)),
                            MediaType.APPLICATION_JSON));
            server.expect(requestTo(org.hamcrest.Matchers.containsString("/v1/roles/assignments/" + ASSIGNMENT_ID)))
                    .andRespond(withStatus(HttpStatus.NO_CONTENT));

            client.revokeRole(USER_ID, "TECH", null);

            server.verify();
        }

        @Test
        @DisplayName("revokes as of now when the caller names no end date")
        void defaultsRevocationToNow() {
            expectRoleLookup("TECH");
            server.expect(requestTo(org.hamcrest.Matchers.containsString("/v1/roles/assignments/user/")))
                    .andRespond(withSuccess(
                            "[" + assignmentJson(ASSIGNMENT_ID, NOW.minusDays(3), null) + "]",
                            MediaType.APPLICATION_JSON));
            server.expect(requestTo("http://security/v1/roles/assignments/%s?endDate=%s".formatted(ASSIGNMENT_ID, NOW)))
                    .andRespond(withStatus(HttpStatus.NO_CONTENT));

            client.revokeRole(USER_ID, "TECH", null);

            server.verify();
        }

        @Test
        @DisplayName("keeps an assignment whose end has not yet arrived revocable")
        void assignmentEndingLaterTodayIsStillLive() {
            expectRoleLookup("TECH");
            server.expect(requestTo(org.hamcrest.Matchers.containsString("/v1/roles/assignments/user/")))
                    .andRespond(withSuccess(
                            "[" + assignmentJson(ASSIGNMENT_ID, NOW.minusDays(3), NOW.plusMinutes(1)) + "]",
                            MediaType.APPLICATION_JSON));
            server.expect(requestTo(org.hamcrest.Matchers.containsString("/v1/roles/assignments/" + ASSIGNMENT_ID)))
                    .andRespond(withStatus(HttpStatus.NO_CONTENT));

            client.revokeRole(USER_ID, "TECH", null);

            server.verify();
        }

        @Test
        @DisplayName("reports not-found when the user holds no live assignment for that role")
        void noMatchingAssignment() {
            expectRoleLookup("TECH");
            server.expect(requestTo(org.hamcrest.Matchers.containsString("/v1/roles/assignments/user/")))
                    .andRespond(withSuccess(
                            "[" + assignmentJson(ASSIGNMENT_ID, NOW.minusDays(30), NOW.minusDays(5)) + "]",
                            MediaType.APPLICATION_JSON));

            assertThatThrownBy(() -> client.revokeRole(USER_ID, "TECH", null))
                    .isInstanceOf(EntityNotFoundException.class);
            server.verify();
        }
    }

    private static String assignmentJson(LocalDateTime start, LocalDateTime end) {
        return assignmentJson(ASSIGNMENT_ID, ROLE_ID, start, end);
    }

    private static String assignmentJson(UUID assignmentId, LocalDateTime start, LocalDateTime end) {
        return assignmentJson(assignmentId, ROLE_ID, start, end);
    }

    /**
     * The pre-#1886 payload: no {@code roleCode}, which is what a pos-security-service older than
     * that change returns. The assignment tests that use it therefore exercise the fallback to
     * the role code the caller named.
     */
    private static String assignmentJson(UUID assignmentId, UUID roleId, LocalDateTime start, LocalDateTime end) {
        return assignmentJson(assignmentId, roleId, null, start, end, null);
    }

    /**
     * pos-security-service's {@code RoleAssignmentDto} on the wire: flat {@code userId}/{@code
     * roleId}/{@code roleCode} over a {@code LocalDateTime} window, with no scope of any kind.
     * {@code revokedAt} is an {@code Instant}, unlike the window.
     */
    private static String assignmentJson(
            UUID assignmentId, UUID roleId, String roleCode, LocalDateTime start, LocalDateTime end, String revokedAt) {
        return """
                {"id":"%s","userId":"%s","roleId":"%s","roleCode":%s,
                 "effectiveStartDate":%s,"effectiveEndDate":%s,
                 "revokedAt":%s,"createdAt":"2026-08-01T00:00:00Z","createdBy":"system"}""".formatted(
                        assignmentId,
                        USER_ID,
                        roleId,
                        quoteOrNull(roleCode),
                        quoteOrNull(start),
                        quoteOrNull(end),
                        quoteOrNull(revokedAt));
    }

    private static String quoteOrNull(Object value) {
        return value == null ? "null" : "\"" + value + "\"";
    }
}
