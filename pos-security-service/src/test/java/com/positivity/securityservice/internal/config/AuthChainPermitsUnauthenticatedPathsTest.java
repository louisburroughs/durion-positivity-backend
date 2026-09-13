package com.positivity.securityservice.internal.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Every endpoint meant to be reachable without a token must be named in the auth filter chain.
 *
 * <h2>What this defends</h2>
 *
 * {@code authSecurityFilterChain} matches {@code /v1/auth/**} and ends in
 * {@code anyRequest().authenticated()}, so a path missing from its allowlist is refused by the
 * chain before the controller is reached — {@code @PreAuthorize("permitAll()")} on the method does
 * not help, because the request never gets that far. That is how {@code /v1/auth/activate-starter}
 * shipped unreachable in review: the annotation said public, the chain said otherwise, and nothing
 * compared the two.
 *
 * <p>Asserted against the configuration source rather than by standing a context up, so it holds
 * for a path added to the controller and forgotten in the chain — which is the actual mistake —
 * without needing a slice per endpoint.
 */
@DisplayName("unauthenticated auth endpoints are permitted by the filter chain")
class AuthChainPermitsUnauthenticatedPathsTest {

    private static final Path CONFIG = Path.of(System.getProperty("user.dir"))
            .resolve("src/main/java/com/positivity/securityservice/internal/config/SecurityConfig.java");

    @ParameterizedTest(name = "{0} is reachable without a token")
    @ValueSource(
            strings = {
                "/v1/auth/login",
                "/v1/auth/self-register",
                "/v1/auth/activate",
                "/v1/auth/activate-starter",
                "/v1/auth/refresh",
                "/v1/auth/validate",
                "/v1/auth/tenants"
            })
    void theChainNamesEveryPublicAuthPath(String path) throws IOException {
        String source = Files.readString(CONFIG, StandardCharsets.UTF_8);

        // Only the requestMatchers(...) argument list counts. Slicing at permitAll() instead would
        // sweep in the constant declarations above it, and the test would then pass for a path that
        // is declared and never used — which is exactly the mistake being guarded against.
        int start = source.indexOf("auth.requestMatchers(");
        assertThat(start)
                .as("the chain's requestMatchers(...) block was not found")
                .isPositive();
        int end = source.indexOf(".permitAll()", start);
        assertThat(end)
                .as("the chain's permitAll() was not found after requestMatchers(")
                .isPositive();
        String matchers = source.substring(start, end);

        // A path is listed either as a literal or through one of this class's own constants.
        Matcher constants = Pattern.compile(
                        "private static final String (\\w+)\\s*=\\s*\"" + Pattern.quote(path) + "\"")
                .matcher(source);
        String constantName = constants.find() ? constants.group(1) : null;

        boolean listed =
                matchers.contains('"' + path + '"') || (constantName != null && matchers.contains(constantName));

        assertThat(listed)
                .as(
                        "%s must be named in authSecurityFilterChain's requestMatchers(...) allowlist — the"
                                + " chain ends in anyRequest().authenticated(), so an unlisted path is refused"
                                + " before the controller's permitAll() annotation is ever consulted%s",
                        path, constantName == null ? "" : " (constant " + constantName + " is declared but unused)")
                .isTrue();
    }
}
