package com.positivity.securityservice.internal.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * #2497: the one place the webhook destination rules live — the URL's shape, then every address its
 * host resolves to — at registration and again before each delivery. DNS is replaced by a map, so
 * every address class is exercised without a network.
 */
@DisplayName("WebhookDestinationPolicy - where audit exports may be sent (#2497)")
class WebhookDestinationPolicyTest {

    private static final String PUBLIC_V4 = "93.184.216.34";
    private static final String PUBLIC_V6 = "2606:4700:4700::1111";

    private final Map<String, InetAddress[]> dns = new HashMap<>();
    private final WebhookDestinationPolicy policy = new WebhookDestinationPolicy(this::resolve, address -> false);

    private InetAddress[] resolve(String host) throws UnknownHostException {
        InetAddress[] answer = dns.get(host);
        if (answer == null) {
            throw new UnknownHostException(host);
        }
        return answer.clone();
    }

    private void dns(String host, String... addresses) throws UnknownHostException {
        InetAddress[] answer = new InetAddress[addresses.length];
        for (int i = 0; i < addresses.length; i++) {
            // A literal: parsed, never looked up.
            answer[i] = InetAddress.getByName(addresses[i]);
        }
        dns.put(host, answer);
    }

    @Test
    @DisplayName("a public https URL whose host resolves only to public addresses is accepted")
    void publicHttpsAccepted() throws Exception {
        dns("siem.example.com", PUBLIC_V4, PUBLIC_V6);

        WebhookDestinationPolicy.Verdict verdict = policy.validate("https://siem.example.com:8443/hooks/audit?x=1");

        assertThat(verdict.accepted()).isTrue();
        assertThat(verdict.problems()).isEmpty();
        assertThat(verdict.uri()).isEqualTo(URI.create("https://siem.example.com:8443/hooks/audit?x=1"));
    }

    @Test
    @DisplayName("surrounding whitespace is ignored and the scheme is case-insensitive")
    void stripsAndIgnoresSchemeCase() throws Exception {
        dns("siem.example.com", PUBLIC_V4);

        assertThat(policy.validate("  HTTPS://siem.example.com/hook ").accepted())
                .isTrue();
    }

    @Nested
    @DisplayName("shape rules")
    class Shape {

        @ParameterizedTest
        @ValueSource(strings = {"", "   "})
        @DisplayName("a blank url is required")
        void blankRefused(String url) {
            assertThat(policy.validate(url).problems()).containsExactly(WebhookDestinationPolicy.REQUIRED);
            assertThat(policy.validate(null).problems()).containsExactly(WebhookDestinationPolicy.REQUIRED);
        }

        @Test
        @DisplayName("a url over 2048 characters is refused")
        void tooLongRefused() throws Exception {
            dns("siem.example.com", PUBLIC_V4);
            String base = "https://siem.example.com/";
            String exact = base + "a".repeat(WebhookDestinationPolicy.MAX_URL_LENGTH - base.length());

            assertThat(policy.validate(exact).accepted()).isTrue();
            assertThat(policy.validate(exact + "a").problems()).containsExactly(WebhookDestinationPolicy.TOO_LONG);
        }

        @ParameterizedTest
        @ValueSource(
                strings = {"siem.example.com/hook", "/hooks/audit", "https:not-hierarchical", "https://bad host/x"})
        @DisplayName("a relative, opaque or unparseable url is refused")
        void notAbsoluteRefused(String url) {
            assertThat(policy.validate(url).problems()).containsExactly(WebhookDestinationPolicy.NOT_A_URL);
        }

        @ParameterizedTest
        @ValueSource(
                strings = {"http://siem.example.com/hook", "ftp://siem.example.com/hook", "wss://siem.example.com/x"})
        @DisplayName("any scheme but https is refused")
        void nonHttpsRefused(String url) {
            assertThat(policy.validate(url).problems()).contains(WebhookDestinationPolicy.NOT_HTTPS);
        }

        @Test
        @DisplayName("user info (credentials in the URL) is refused, also beside a host the URI parser rejects")
        void userInfoRefused() {
            assertThat(policy.validate("https://user:secret@siem.example.com/hook")
                            .problems())
                    .containsExactly(WebhookDestinationPolicy.USER_INFO);
            assertThat(policy.validate("https://user@bad_host.example.com/hook").problems())
                    .contains(WebhookDestinationPolicy.USER_INFO, WebhookDestinationPolicy.NO_HOST);
        }

        @Test
        @DisplayName("a fragment is refused")
        void fragmentRefused() {
            assertThat(policy.validate("https://siem.example.com/hook#part").problems())
                    .containsExactly(WebhookDestinationPolicy.FRAGMENT);
        }

        @ParameterizedTest
        @ValueSource(strings = {"https:///hook", "https://bad_host.example.com/hook"})
        @DisplayName("a url without a usable host is refused")
        void noHostRefused(String url) {
            assertThat(policy.validate(url).problems()).contains(WebhookDestinationPolicy.NO_HOST);
        }

        @ParameterizedTest
        @ValueSource(
                strings = {
                    "https://93.184.216.34/hook",
                    "https://127.0.0.1/hook",
                    "https://2130706433/hook",
                    "https://017700000001/hook",
                    "https://[::1]/hook",
                    "https://[2606:4700:4700::1111]/hook"
                })
        @DisplayName("an IP literal, IPv4 or IPv6, in any notation, is refused even when public")
        void ipLiteralRefused(String url) {
            assertThat(policy.validate(url).problems()).containsExactly(WebhookDestinationPolicy.IP_LITERAL);
        }

        @ParameterizedTest
        @ValueSource(strings = {"https://0x7f.1/hook", "https://127.1/hook", "https://10.1/hook"})
        @DisplayName("short and hex IPv4 forms never reach a lookup: the URI parser yields no host for them")
        void shortIpv4FormsRefused(String url) {
            assertThat(policy.validate(url).accepted()).isFalse();
            assertThat(policy.validate(url).problems()).isNotEmpty();
        }

        @Test
        @DisplayName("every broken rule is reported, not just the first")
        void reportsEveryProblem() {
            assertThat(policy.validate("http://user@siem.example.com/hook#frag").problems())
                    .containsExactly(
                            WebhookDestinationPolicy.NOT_HTTPS,
                            WebhookDestinationPolicy.USER_INFO,
                            WebhookDestinationPolicy.FRAGMENT);
        }
    }

    @Nested
    @DisplayName("resolved addresses")
    class Addresses {

        @ParameterizedTest(name = "{0}")
        @ValueSource(
                strings = {
                    "0.0.0.0", // any-local
                    "0.1.2.3", // this network
                    "127.0.0.1", // loopback
                    "127.8.9.10",
                    "10.0.0.7", // private
                    "172.16.3.4",
                    "172.31.255.255",
                    "192.168.1.1",
                    "169.254.169.254", // cloud metadata (link-local)
                    "169.254.0.1",
                    "100.64.0.1", // carrier-grade NAT
                    "100.127.255.254",
                    "100.100.100.200", // a CGNAT-range metadata endpoint
                    "192.0.0.1", // IETF protocol assignments
                    "192.0.2.10", // documentation
                    "198.18.0.1", // benchmarking
                    "198.51.100.7",
                    "203.0.113.9",
                    "224.0.0.1", // multicast
                    "239.255.255.250",
                    "240.0.0.1", // reserved
                    "255.255.255.255" // broadcast
                })
        @DisplayName("IPv4: a refused class is not publicly routable")
        void refusedIpv4(String address) throws Exception {
            dns("hook.example.com", address);

            assertThat(WebhookDestinationPolicy.isPublic(InetAddress.getByName(address)))
                    .isFalse();
            assertThat(policy.validate("https://hook.example.com/x").problems())
                    .containsExactly(WebhookDestinationPolicy.NOT_PUBLIC);
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(
                strings = {
                    "::", // any-local
                    "::1", // loopback
                    "::127.0.0.1", // IPv4-compatible
                    "::10.0.0.1",
                    "fe80::1", // link-local
                    "fec0::1", // site-local
                    "fc00::1", // unique local
                    "fd00:ec2::254", // AWS metadata over IPv6
                    "ff02::1", // multicast
                    "2002:7f00:1::", // 6to4 of 127.0.0.1
                    "2002:a9fe:a9fe::", // 6to4 of 169.254.169.254
                    "64:ff9b::a00:1", // NAT64 of 10.0.0.1
                    "64:ff9b:1::1", // local-use NAT64
                    "2001:db8::1", // documentation
                    "2001::1", // Teredo
                    "100::1" // discard-only
                })
        @DisplayName("IPv6: a refused class is not publicly routable")
        void refusedIpv6(String address) throws Exception {
            dns("hook.example.com", address);

            assertThat(policy.validate("https://hook.example.com/x").problems())
                    .containsExactly(WebhookDestinationPolicy.NOT_PUBLIC);
        }

        @Test
        @DisplayName("an IPv4-mapped IPv6 answer is judged as the IPv4 address it carries")
        void mappedIpv6() throws Exception {
            dns("hook.example.com", "::ffff:127.0.0.1");
            assertThat(policy.validate("https://hook.example.com/x").accepted()).isFalse();

            dns("hook.example.com", "::ffff:" + PUBLIC_V4);
            assertThat(policy.validate("https://hook.example.com/x").accepted()).isTrue();
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {PUBLIC_V4, "8.8.8.8", "1.1.1.1", PUBLIC_V6, "2002:808:808::", "64:ff9b::808:808"})
        @DisplayName("public IPv4 and IPv6 answers, including tunnels of a public IPv4, are accepted")
        void publicAccepted(String address) throws Exception {
            dns("hook.example.com", address);

            assertThat(policy.validate("https://hook.example.com/x").accepted()).isTrue();
        }

        @Test
        @DisplayName("one refused address among public ones refuses the host: the connection may use any")
        void anyRefusedAddressRefuses() throws Exception {
            dns("hook.example.com", PUBLIC_V4, PUBLIC_V6, "10.1.2.3");

            assertThat(policy.validate("https://hook.example.com/x").problems())
                    .containsExactly(WebhookDestinationPolicy.NOT_PUBLIC);
        }

        @Test
        @DisplayName("a DNS name that resolves to a private address (localhost, an internal service) is refused")
        void namesResolvingPrivateRefused() throws Exception {
            dns("localhost", "127.0.0.1", "::1");
            dns("pos-api-gateway", "172.18.0.5");
            dns("metadata.google.internal", "169.254.169.254");

            assertThat(policy.validate("https://localhost:8080/x").accepted()).isFalse();
            assertThat(policy.validate("https://pos-api-gateway:8080/x").accepted())
                    .isFalse();
            assertThat(policy.validate("https://metadata.google.internal/computeMetadata/v1/")
                            .accepted())
                    .isFalse();
        }

        @Test
        @DisplayName("a host that does not resolve, or answers nothing, is refused")
        void unresolvedRefused() {
            assertThat(policy.validate("https://nowhere.example.com/x").problems())
                    .containsExactly(WebhookDestinationPolicy.UNRESOLVED);
            dns.put("empty.example.com", new InetAddress[0]);
            assertThat(policy.validate("https://empty.example.com/x").problems())
                    .containsExactly(WebhookDestinationPolicy.NOT_PUBLIC);
        }
    }

    @Nested
    @DisplayName("delivery-time check")
    class Delivery {

        @Test
        @DisplayName("re-resolves the host: a name that turned private after registration (DNS rebinding) is refused")
        void rebindingRefused() throws Exception {
            dns("hook.example.com", PUBLIC_V4);
            URI destination = policy.validate("https://hook.example.com/x").uri();
            assertThat(destination).isNotNull();

            dns("hook.example.com", "169.254.169.254");

            assertThatThrownBy(() -> policy.checkDelivery(destination))
                    .isInstanceOf(WebhookDestinationPolicy.DestinationRefusedException.class)
                    .hasMessage(WebhookDestinationPolicy.NOT_PUBLIC);
        }

        @Test
        @DisplayName("a public answer passes; a vanished name is an UnknownHostException, worth retrying")
        void publicPassesUnresolvedThrows() throws Exception {
            dns("hook.example.com", PUBLIC_V4);
            URI destination = URI.create("https://hook.example.com/x");
            assertThatCode(() -> policy.checkDelivery(destination)).doesNotThrowAnyException();

            dns.remove("hook.example.com");
            assertThatThrownBy(() -> policy.checkDelivery(destination)).isInstanceOf(UnknownHostException.class);
        }

        @Test
        @DisplayName("a stored URL that breaks a shape rule is refused before any lookup")
        void storedShapeRechecked() {
            assertThatThrownBy(() -> policy.checkDelivery(URI.create("http://hook.example.com/x")))
                    .isInstanceOf(WebhookDestinationPolicy.DestinationRefusedException.class)
                    .hasMessageContaining(WebhookDestinationPolicy.NOT_HTTPS);
        }

        @Test
        @DisplayName("the test seam admits only what it names: production's public constructor admits no loopback")
        void seamIsTestOnly() throws Exception {
            WebhookDestinationPolicy loopbackForTests =
                    new WebhookDestinationPolicy(this::loopback, InetAddress::isLoopbackAddress);
            assertThatCode(() -> loopbackForTests.checkDelivery(URI.create("https://localhost:9443/x")))
                    .doesNotThrowAnyException();

            assertThatThrownBy(
                            () -> new WebhookDestinationPolicy().checkDelivery(URI.create("https://localhost:9443/x")))
                    .isInstanceOf(WebhookDestinationPolicy.DestinationRefusedException.class);
        }

        private InetAddress[] loopback(String host) {
            return new InetAddress[] {InetAddress.getLoopbackAddress()};
        }
    }
}
