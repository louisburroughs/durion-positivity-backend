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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Set;
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
 * every value is fixed or module-internal (the username-less {@code GET /v1/users}, the
 * hardcoded LOCATION/GLOBAL {@code scopeType}) is a {@link SecurityServiceContractException}
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
 */
@DisplayName("SecurityServiceClient — RBAC lookups and assignment lifecycle")
class SecurityServiceClientTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000e1");
    private static final UUID ROLE_ID = UUID.fromString("00000000-0000-0000-0000-0000000000e2");
    private static final UUID ASSIGNMENT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000e3");
    private static final UUID LOCATION_ID = UUID.fromString("00000000-0000-0000-0000-0000000000e4");
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 11);

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
        @DisplayName("passes the scope through as a query parameter")
        void rolesByScope() {
            server.expect(requestTo("http://security/v1/roles?scopeType=LOCATION"))
                    .andRespond(withSuccess("""
                            [{"code":"TECH","name":"Technician","description":"d",
                              "scopeType":"LOCATION","active":true}]""", MediaType.APPLICATION_JSON));

            assertThat(client.getAvailableRoles("LOCATION"))
                    .singleElement()
                    .extracting(r -> r.getCode())
                    .isEqualTo("TECH");
            server.verify();
        }

        @Test
        @DisplayName("maps a missing roles endpoint to not-found")
        void rolesFailureClassification() {
            server.expect(requestTo("http://security/v1/roles?scopeType=LOCATION"))
                    .andRespond(withResourceNotFound());
            assertThatThrownBy(() -> client.getAvailableRoles("LOCATION")).isInstanceOf(EntityNotFoundException.class);
            server.verify();
        }

        @Test
        @DisplayName("a downstream 400 is a contract violation, not a caller error — scope is a fixed constant")
        void rolesA400IsAContractViolationNotCallerError() {
            // The only two callers (PeopleAccessControlServiceImpl) pass the hardcoded constants
            // "LOCATION"/"GLOBAL" — never end-user input — so a 400 here can only mean this
            // module's own contract with pos-security-service has drifted.
            server.expect(requestTo("http://security/v1/roles?scopeType=LOCATION"))
                    .andRespond(withBadRequest());
            assertThatThrownBy(() -> client.getAvailableRoles("LOCATION"))
                    .isInstanceOf(SecurityServiceContractException.class)
                    .isNotInstanceOf(PeopleContactValidationException.class)
                    .isNotInstanceOf(SecurityServiceException.class);
            server.verify();
        }

        @Test
        @DisplayName("forwards the history and end-date filters to the assignments endpoint")
        void assignmentsQuery() {
            server.expect(requestTo(
                            "http://security/v1/roles/assignments/user/%s?includeHistory=true&endDate=2026-08-31T00:00"
                                    .formatted(USER_ID)))
                    .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

            assertThat(client.getUserRoleAssignments(USER_ID, true, LocalDateTime.of(2026, 8, 31, 0, 0)))
                    .isEmpty();
            server.verify();
        }

        @Test
        @DisplayName("treats a null assignments body as a failure rather than as no assignments")
        void nullAssignmentsBodyIsAFailure() {
            server.expect(requestTo(org.hamcrest.Matchers.containsString("/v1/roles/assignments/user/")))
                    .andRespond(withSuccess("null", MediaType.APPLICATION_JSON));

            assertThatThrownBy(() -> client.getUserRoleAssignments(USER_ID, false, null))
                    .isInstanceOf(IllegalStateException.class);
            server.verify();
        }
    }

    @Nested
    @DisplayName("assignRole")
    class Assign {

        @Test
        @DisplayName("resolves the role name to an id and scopes the assignment to the given locations")
        void locationScopedAssignment() {
            expectRoleLookup("TECH");
            server.expect(requestTo("http://security/v1/roles/assignments"))
                    .andExpect(method(HttpMethod.POST))
                    .andExpect(jsonPath("$.userId").value(USER_ID.toString()))
                    .andExpect(jsonPath("$.roleId").value(ROLE_ID.toString()))
                    // Supplying any location makes this a LOCATION-scoped grant, not a global one.
                    .andExpect(jsonPath("$.scopeType").value("LOCATION"))
                    .andExpect(jsonPath("$.scopeLocationIds[0]").value(LOCATION_ID.toString()))
                    .andRespond(withSuccess(assignmentJson(TODAY, null, "LOCATION"), MediaType.APPLICATION_JSON));

            UserRoleDto result = client.assignRole(UserRoleAssignmentRequest.builder()
                    .userId(USER_ID)
                    .roleCode("TECH")
                    .locationIds(Set.of(LOCATION_ID))
                    .startDate(LocalDateTime.of(2026, 8, 11, 0, 0))
                    .build());

            assertThat(result.getRoleCode()).isEqualTo("TECH");
            assertThat(result.getUserId()).isEqualTo(USER_ID.toString());
            assertThat(result.getLocationId()).isEqualTo(LOCATION_ID);
            assertThat(result.getActive()).isTrue();
            server.verify();
        }

        @Test
        @DisplayName("falls back to a GLOBAL scope when no location is named")
        void globalAssignment() {
            expectRoleLookup("ADMIN");
            server.expect(requestTo("http://security/v1/roles/assignments"))
                    // No location means the grant applies everywhere; sending LOCATION with an
                    // empty set would silently grant nothing.
                    .andExpect(jsonPath("$.scopeType").value("GLOBAL"))
                    .andExpect(jsonPath("$.scopeLocationIds").doesNotExist())
                    .andRespond(withSuccess(assignmentJson(TODAY, null, "GLOBAL"), MediaType.APPLICATION_JSON));

            assertThat(client.assignRole(UserRoleAssignmentRequest.builder()
                                    .userId(USER_ID)
                                    .roleCode("ADMIN")
                                    .build())
                            .getLocationId())
                    .isNull();
            server.verify();
        }

        @Test
        @DisplayName("reports an assignment that has already ended as inactive")
        void endedAssignmentIsInactive() {
            expectRoleLookup("TECH");
            server.expect(requestTo("http://security/v1/roles/assignments"))
                    .andRespond(withSuccess(
                            assignmentJson(TODAY.minusDays(10), TODAY.minusDays(1), "GLOBAL"),
                            MediaType.APPLICATION_JSON));

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
                    .andRespond(
                            withSuccess(assignmentJson(TODAY.plusDays(1), null, "GLOBAL"), MediaType.APPLICATION_JSON));

            assertThat(client.assignRole(UserRoleAssignmentRequest.builder()
                                    .userId(USER_ID)
                                    .roleCode("TECH")
                                    .build())
                            .getActive())
                    .isFalse();
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
                                            assignmentJson(expiredAssignment, TODAY.minusDays(30), TODAY.minusDays(5)),
                                            assignmentJson(ASSIGNMENT_ID, TODAY.minusDays(3), null)),
                            MediaType.APPLICATION_JSON));
            // The already-ended assignment must not be the one revoked, or the live grant survives.
            server.expect(requestTo(
                            "http://security/v1/roles/assignments/%s?endDate=2026-08-20".formatted(ASSIGNMENT_ID)))
                    .andExpect(method(HttpMethod.DELETE))
                    .andRespond(withStatus(HttpStatus.NO_CONTENT));

            client.revokeRole(USER_ID, "TECH", LocalDateTime.of(2026, 8, 20, 0, 0));

            server.verify();
        }

        @Test
        @DisplayName("revokes as of today when the caller names no end date")
        void defaultsRevocationToToday() {
            expectRoleLookup("TECH");
            server.expect(requestTo(org.hamcrest.Matchers.containsString("/v1/roles/assignments/user/")))
                    .andRespond(withSuccess(
                            "[" + assignmentJson(ASSIGNMENT_ID, TODAY.minusDays(3), null) + "]",
                            MediaType.APPLICATION_JSON));
            server.expect(requestTo(
                            "http://security/v1/roles/assignments/%s?endDate=%s".formatted(ASSIGNMENT_ID, TODAY)))
                    .andRespond(withStatus(HttpStatus.NO_CONTENT));

            client.revokeRole(USER_ID, "TECH", null);

            server.verify();
        }

        @Test
        @DisplayName("keeps an assignment ending today revocable, since it has not ended yet")
        void assignmentEndingTodayIsStillLive() {
            expectRoleLookup("TECH");
            server.expect(requestTo(org.hamcrest.Matchers.containsString("/v1/roles/assignments/user/")))
                    .andRespond(withSuccess(
                            "[" + assignmentJson(ASSIGNMENT_ID, TODAY.minusDays(3), TODAY) + "]",
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
                            "[" + assignmentJson(ASSIGNMENT_ID, TODAY.minusDays(30), TODAY.minusDays(5)) + "]",
                            MediaType.APPLICATION_JSON));

            assertThatThrownBy(() -> client.revokeRole(USER_ID, "TECH", null))
                    .isInstanceOf(EntityNotFoundException.class);
            server.verify();
        }
    }

    private static String assignmentJson(LocalDate start, LocalDate end, String scopeType) {
        return assignmentJson(ASSIGNMENT_ID, start, end, scopeType);
    }

    private static String assignmentJson(UUID assignmentId, LocalDate start, LocalDate end) {
        return assignmentJson(assignmentId, start, end, "GLOBAL");
    }

    private static String assignmentJson(UUID assignmentId, LocalDate start, LocalDate end, String scopeType) {
        return """
                {"id":"%s","user":{"id":"%s","username":"ada"},
                 "role":{"id":"%s","name":"TECH","description":"d"},
                 "scopeType":"%s","scopeLocationIds":%s,
                 "effectiveStartDate":%s,"effectiveEndDate":%s,"createdAt":"2026-08-01T00:00:00Z"}""".formatted(
                        assignmentId,
                        USER_ID,
                        ROLE_ID,
                        scopeType,
                        "LOCATION".equals(scopeType) ? "[\"" + LOCATION_ID + "\"]" : "null",
                        start == null ? "null" : "\"" + start + "\"",
                        end == null ? "null" : "\"" + end + "\"");
    }
}
