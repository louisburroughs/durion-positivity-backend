package com.positivity.security.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Tests for {@link SecurityContextHelper}, which was at 0% coverage.
 *
 * <p>
 * Every downstream service reads the caller's identity through this class. The
 * gateway is the only authenticator (ADR-0011/0014); it decodes a JWT into
 * headers, a filter turns those into an {@link Authentication}, and from that
 * point on "who is this and what may they do" is whatever these static methods
 * say. Two properties matter more than coverage:
 *
 * <ul>
 * <li><b>It must fail loudly rather than fall back.</b> Every accessor throws
 * when the context is absent, unauthenticated or anonymous. A helper that
 * returned an empty {@code Optional} or an empty authority set in those cases
 * would turn a missing security context into "this user has no permissions",
 * which reads as a normal authorization failure — and the same shape, one
 * inverted check away, is "this user passes every check".</li>
 * <li><b>The audit identity is not the login name.</b> {@code userId} is a UUID
 * carried in the authentication details and is what audit rows and
 * {@code createdBy} columns are written from. A non-UUID or absent value is
 * rejected outright rather than coerced, because a coerced identity is
 * unattributable after the fact.</li>
 * </ul>
 */
@DisplayName("SecurityContextHelper — reading the gateway-supplied identity")
class SecurityContextHelperTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticate(Object principal, Object details, String... authorities) {
        var token = new UsernamePasswordAuthenticationToken(
                principal,
                null,
                List.of(authorities).stream().map(SimpleGrantedAuthority::new).toList());
        token.setDetails(details);
        SecurityContextHolder.getContext().setAuthentication(token);
    }

    private static Map<String, Object> details(String username, Object userId) {
        return Map.of(
                GatewaySecurityConstants.DETAIL_USERNAME, username,
                GatewaySecurityConstants.DETAIL_USER_ID, userId);
    }

    @Nested
    @DisplayName("refusing to answer without a real caller")
    class FailsClosed {

        @Test
        @DisplayName("an empty security context is an error, not an anonymous user")
        void noAuthenticationThrows() {
            // The distinction matters: returning "no authorities" here would let a caller with
            // no security context at all look identical to a real user who simply lacks a
            // permission, and the failure would surface as a 403 nobody investigates.
            assertThatThrownBy(SecurityContextHelper::getAuthorities)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("no Authentication");
        }

        @Test
        @DisplayName("an unauthenticated token is rejected even though it exists")
        void unauthenticatedTokenThrows() {
            var token = new UsernamePasswordAuthenticationToken("jsmith", "credentials");
            token.setAuthenticated(false);
            SecurityContextHolder.getContext().setAuthentication(token);

            assertThatThrownBy(SecurityContextHelper::getAuthorities)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("not authenticated");
        }

        @Test
        @DisplayName("the anonymous principal is rejected rather than treated as a user")
        void anonymousIsRejected() {
            var anonymous = new AnonymousAuthenticationToken(
                    "key",
                    GatewaySecurityConstants.ANONYMOUS_USER,
                    List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS")));
            SecurityContextHolder.getContext().setAuthentication(anonymous);

            // Spring marks anonymous tokens as authenticated, so the isAuthenticated() check
            // above does not catch this one. Without the explicit principal check, an
            // unauthenticated request would read as a user named "anonymousUser" and be written
            // into audit rows as if it were one.
            assertThatThrownBy(SecurityContextHelper::getAuthorities)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Anonymous");
        }

        @Test
        @DisplayName("authentication with no details map is an error")
        void missingDetailsMapThrows() {
            authenticate("jsmith", null, "crm:party:view");

            assertThatThrownBy(SecurityContextHelper::getCurrentUsername)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("details map is missing");
        }

        @Test
        @DisplayName("details that are not a map are an error rather than being ignored")
        void nonMapDetailsThrows() {
            authenticate("jsmith", "some-string-details", "crm:party:view");

            assertThatThrownBy(SecurityContextHelper::getCurrentUsername)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("details map is missing");
        }
    }

    @Nested
    @DisplayName("username resolution")
    class Username {

        @Test
        @DisplayName("the details map wins over the principal")
        void detailsUsernameTakesPrecedence() {
            authenticate("principal-name", details("details-name", USER_ID));

            // The gateway writes the details map from the validated token; the principal is
            // whatever the filter happened to construct. When they disagree the token wins.
            assertThat(SecurityContextHelper.getCurrentUsername()).contains("details-name");
        }

        @Test
        @DisplayName("a string principal is used when the details carry no username")
        void principalIsTheFallback() {
            authenticate("jsmith", Map.of(GatewaySecurityConstants.DETAIL_USER_ID, USER_ID));

            assertThat(SecurityContextHelper.getCurrentUsername()).contains("jsmith");
        }

        @ParameterizedTest(name = "a details username of [{0}] falls through to the principal")
        @ValueSource(strings = {"", "   "})
        @DisplayName("a blank username in the details is not a username")
        void blankDetailsUsernameFallsThrough(String blank) {
            authenticate("jsmith", Map.of(GatewaySecurityConstants.DETAIL_USERNAME, blank));

            // A blank value is worse than a missing one: it would be written into audit columns
            // as an empty attribution while looking like a successful lookup.
            assertThat(SecurityContextHelper.getCurrentUsername()).contains("jsmith");
        }

        @Test
        @DisplayName("a non-string principal is resolved through toString")
        void nonStringPrincipalUsesToString() {
            Object principal = new Object() {
                @Override
                public String toString() {
                    return "service-account";
                }
            };
            authenticate(principal, Map.of());

            assertThat(SecurityContextHelper.getCurrentUsername()).contains("service-account");
        }

        @Test
        @DisplayName("an authenticated context with nothing usable is an error, not an empty Optional")
        void nothingUsableThrows() {
            authenticate(null, Map.of());

            // Returning Optional.empty() here would let a caller quietly write null into a
            // createdBy column rather than discovering the context was malformed.
            assertThatThrownBy(SecurityContextHelper::getCurrentUsername)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("no username was found");
        }

        @Test
        @DisplayName("the defaulting variant swallows the failure and returns its default")
        void defaultingVariantSwallows() {
            // Deliberately the opposite policy, for the callers that legitimately run without a
            // user — startup runners and event consumers writing "system" into audit columns.
            assertThat(SecurityContextHelper.getCurrentUsernameOrDefault("system"))
                    .isEqualTo("system");
        }

        @Test
        @DisplayName("the defaulting variant still prefers a real username when there is one")
        void defaultingVariantPrefersRealUser() {
            authenticate("jsmith", details("jsmith", USER_ID));

            assertThat(SecurityContextHelper.getCurrentUsernameOrDefault("system"))
                    .isEqualTo("jsmith");
        }
    }

    @Nested
    @DisplayName("the audit user id")
    class UserId {

        @Test
        @DisplayName("a UUID in the details is returned")
        void uuidIsReturned() {
            authenticate("jsmith", details("jsmith", USER_ID));

            assertThat(SecurityContextHelper.getCurrentUserIdAsUuid()).contains(USER_ID);
        }

        @Test
        @DisplayName("a missing user id is rejected rather than returned as empty")
        void missingUserIdThrows() {
            authenticate("jsmith", Map.of(GatewaySecurityConstants.DETAIL_USERNAME, "jsmith"));

            assertThatThrownBy(SecurityContextHelper::getCurrentUserIdAsUuid)
                    .isInstanceOf(MissingPersonIdException.class)
                    .hasMessageContaining(GatewaySecurityConstants.DETAIL_USER_ID);
        }

        @Test
        @DisplayName("a user id of the wrong type is rejected rather than parsed")
        void wrongTypeUserIdThrows() {
            authenticate("jsmith", Map.of(GatewaySecurityConstants.DETAIL_USER_ID, USER_ID.toString()));

            // A String that happens to look like a UUID is still rejected. Coercing it would
            // mean the audit identity depends on how the filter chose to box the value, and a
            // filter that started sending something else would degrade silently.
            assertThatThrownBy(SecurityContextHelper::getCurrentUserIdAsUuid)
                    .isInstanceOf(MissingPersonIdException.class)
                    .hasMessageContaining("Expected UUID");
        }

        @Test
        @DisplayName("the defaulting variant returns its default for an unauthenticated caller")
        void defaultingVariantReturnsDefault() {
            UUID fallback = UUID.fromString("00000000-0000-0000-0000-0000000000ff");

            assertThat(SecurityContextHelper.getCurrentUserIdAsUuidOrDefault(fallback))
                    .isEqualTo(fallback);
        }

        @Test
        @DisplayName("the defaulting variant also swallows a malformed user id")
        void defaultingVariantSwallowsMalformed() {
            UUID fallback = UUID.fromString("00000000-0000-0000-0000-0000000000ff");
            authenticate("jsmith", Map.of(GatewaySecurityConstants.DETAIL_USER_ID, 42));

            // Worth pinning explicitly: the defaulting variant catches the malformed case too,
            // so a caller that uses it will never learn the context was broken.
            assertThat(SecurityContextHelper.getCurrentUserIdAsUuidOrDefault(fallback))
                    .isEqualTo(fallback);
        }

        @Test
        @DisplayName("the throwing variant is the one to use where attribution is required")
        void throwingVariantThrows() {
            authenticate("jsmith", Map.of(GatewaySecurityConstants.DETAIL_USERNAME, "jsmith"));

            assertThatThrownBy(SecurityContextHelper::getCurrentUserIdAsUuidOrThrowIllegalStateException)
                    .isInstanceOf(MissingPersonIdException.class);
        }
    }

    @Nested
    @DisplayName("authority checks")
    class Authorities {

        @Test
        @DisplayName("authorities are read straight off the token")
        void authoritiesAreRead() {
            authenticate("jsmith", details("jsmith", USER_ID), "crm:party:view", "crm:party:edit");

            assertThat(SecurityContextHelper.getAuthorities())
                    .containsExactlyInAnyOrder("crm:party:view", "crm:party:edit");
        }

        @Test
        @DisplayName("hasAuthority is exact, not a prefix or substring match")
        void hasAuthorityIsExact() {
            authenticate("jsmith", details("jsmith", USER_ID), "crm:party:view");

            assertThat(SecurityContextHelper.hasAuthority("crm:party:view")).isTrue();
            // The near-misses are the point. A prefix or contains match would make
            // "crm:party:view" grant "crm:party:view_sensitive", which is exactly the kind of
            // permission a narrower one is meant to exclude.
            assertThat(SecurityContextHelper.hasAuthority("crm:party")).isFalse();
            assertThat(SecurityContextHelper.hasAuthority("crm:party:view_sensitive"))
                    .isFalse();
            assertThat(SecurityContextHelper.hasAuthority("CRM:PARTY:VIEW")).isFalse();
        }

        @Test
        @DisplayName("hasAnyAuthority needs one, hasAllAuthorities needs every one")
        void anyVersusAll() {
            authenticate("jsmith", details("jsmith", USER_ID), "crm:party:view", "crm:party:edit");

            assertThat(SecurityContextHelper.hasAnyAuthority("crm:party:delete", "crm:party:view"))
                    .isTrue();
            assertThat(SecurityContextHelper.hasAllAuthorities("crm:party:view", "crm:party:edit"))
                    .isTrue();
            // One missing authority is enough to fail the all-check — the loop must not stop at
            // the first match.
            assertThat(SecurityContextHelper.hasAllAuthorities("crm:party:view", "crm:party:delete"))
                    .isFalse();
            assertThat(SecurityContextHelper.hasAnyAuthority("crm:party:delete"))
                    .isFalse();
        }

        @Test
        @DisplayName("an empty varargs list is vacuously true for all and false for any")
        void emptyVarargsBoundary() {
            authenticate("jsmith", details("jsmith", USER_ID), "crm:party:view");

            // Worth pinning because the two disagree, and a caller that builds its authority
            // list dynamically can reach this case: hasAllAuthorities() with nothing required
            // passes, hasAnyAuthority() with nothing offered does not.
            assertThat(SecurityContextHelper.hasAllAuthorities()).isTrue();
            assertThat(SecurityContextHelper.hasAnyAuthority()).isFalse();
        }

        @Test
        @DisplayName("hasRole adds the ROLE_ prefix, and tolerates one already being there")
        void hasRoleNormalisesThePrefix() {
            authenticate("jsmith", details("jsmith", USER_ID), "ROLE_ADMIN");

            assertThat(SecurityContextHelper.hasRole("ADMIN")).isTrue();
            assertThat(SecurityContextHelper.hasRole("ROLE_ADMIN")).isTrue();
            // Double-prefixing would silently deny every caller that passed the long form.
            assertThat(SecurityContextHelper.hasRole("ROLE_ROLE_ADMIN")).isFalse();
            assertThat(SecurityContextHelper.hasRole("USER")).isFalse();
        }
    }
}
