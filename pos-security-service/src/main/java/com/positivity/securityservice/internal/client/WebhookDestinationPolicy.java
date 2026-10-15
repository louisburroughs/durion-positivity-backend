package com.positivity.securityservice.internal.client;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Where a tenant's audit exports may be sent (#2497): the one place the destination rules live, applied
 * both when a webhook is registered and again right before every delivery attempt.
 *
 * <p>A destination is an absolute {@code https} URL of at most {@value #MAX_URL_LENGTH} characters,
 * with a DNS host name (never an IP literal), no user info and no fragment, whose host resolves only to
 * publicly routable addresses. Refused: any-local, loopback, link-local (which holds the
 * {@code 169.254.169.254} cloud metadata endpoint), site-local and private ({@code 10/8},
 * {@code 172.16/12}, {@code 192.168/16}), carrier-grade NAT ({@code 100.64/10}), unique local IPv6
 * ({@code fc00::/7}, which holds the IPv6 metadata endpoint {@code fd00:ec2::254}), multicast, the
 * reserved and documentation ranges, and IPv6 forms that embed or tunnel an IPv4 address unless the
 * embedded address is itself public. A host with even one refused address is refused: the connection
 * may use any of them.
 *
 * <p>At delivery time {@link #checkDelivery(URI)} resolves the host again, immediately before the
 * request is sent. The JVM's address cache then hands the HTTP client the addresses just checked, which
 * blunts DNS rebinding (a name that answered with a public address at registration and a private one
 * later). Redirects are never followed by the sender, so a 3xx cannot move a delivery elsewhere.
 */
@Component
public class WebhookDestinationPolicy {

    /** Longest URL a destination may have. */
    public static final int MAX_URL_LENGTH = 2048;

    static final String REQUIRED = "url is required";
    static final String TOO_LONG = "url must be at most " + MAX_URL_LENGTH + " characters";
    static final String NOT_A_URL = "url must be an absolute URL";
    static final String NOT_HTTPS = "url must use https";
    static final String USER_INFO = "url must not carry user info (credentials)";
    static final String FRAGMENT = "url must not carry a fragment";
    static final String NO_HOST = "url must name a host";
    static final String IP_LITERAL = "url must name its host by DNS name, not by IP address";
    static final String UNRESOLVED = "url host does not resolve";
    static final String NOT_PUBLIC = "url host resolves to an address that is not publicly routable"
            + " (loopback, private, link-local, multicast, reserved or cloud metadata)";

    /** Dotted, decimal or hex forms a resolver would read as an IPv4 address ({@code 2130706433}, {@code 0x7f.1}). */
    private static final Pattern IPV4_LIKE =
            Pattern.compile("^(0x[0-9a-f]*|[0-9]+)(\\.(0x[0-9a-f]*|[0-9]+))*\\.?$", Pattern.CASE_INSENSITIVE);

    /** Resolves a host name to every address it answers with. */
    @FunctionalInterface
    interface HostResolver {
        InetAddress @NonNull [] resolve(@NonNull String host) throws UnknownHostException;
    }

    private final HostResolver resolver;
    private final Predicate<InetAddress> allowedForTests;

    @Autowired
    public WebhookDestinationPolicy() {
        this(InetAddress::getAllByName, address -> false);
    }

    /**
     * Test seam: {@code resolver} replaces DNS, and {@code allowedForTests} admits addresses the rules
     * would refuse (a test's local HTTPS server on {@code 127.0.0.1}). Production uses the public
     * constructor, which admits nothing extra.
     */
    WebhookDestinationPolicy(@NonNull HostResolver resolver, @NonNull Predicate<InetAddress> allowedForTests) {
        this.resolver = resolver;
        this.allowedForTests = allowedForTests;
    }

    /**
     * Registration-time check of a caller-supplied URL: the shape rules, then the host's resolved
     * addresses.
     *
     * @return the parsed destination, or every problem found, one message each
     */
    public @NonNull Verdict validate(@Nullable String url) {
        Shape shape = shape(url);
        if (!shape.problems().isEmpty()) {
            return Verdict.refused(shape.problems());
        }
        URI uri = shape.uri();
        try {
            if (!allPublic(resolver.resolve(uri.getHost()))) {
                return Verdict.refused(List.of(NOT_PUBLIC));
            }
        } catch (UnknownHostException e) {
            return Verdict.refused(List.of(UNRESOLVED));
        }
        return Verdict.accepted(uri);
    }

    /**
     * Delivery-time check, run immediately before each attempt: the stored URL still has a valid shape
     * and its host, resolved now, answers only with addresses the rules allow.
     *
     * @throws UnknownHostException when the host does not resolve now (worth retrying)
     * @throws DestinationRefusedException when the URL or an address it resolves to is refused (not
     *     worth retrying: nothing is sent)
     */
    public void checkDelivery(@NonNull URI destination) throws UnknownHostException {
        Shape shape = shape(destination.toString());
        if (!shape.problems().isEmpty()) {
            throw new DestinationRefusedException(String.join("; ", shape.problems()));
        }
        if (!allPublic(resolver.resolve(destination.getHost()))) {
            throw new DestinationRefusedException(NOT_PUBLIC);
        }
    }

    private boolean allPublic(InetAddress @NonNull [] addresses) {
        if (addresses.length == 0) {
            return false;
        }
        for (InetAddress address : addresses) {
            if (!isPublic(address) && !allowedForTests.test(address)) {
                return false;
            }
        }
        return true;
    }

    private static @NonNull Shape shape(@Nullable String url) {
        if (url == null || url.isBlank()) {
            return Shape.problems(List.of(REQUIRED));
        }
        String candidate = url.strip();
        if (candidate.length() > MAX_URL_LENGTH) {
            return Shape.problems(List.of(TOO_LONG));
        }
        URI uri;
        try {
            uri = new URI(candidate);
        } catch (URISyntaxException e) {
            return Shape.problems(List.of(NOT_A_URL));
        }
        if (!uri.isAbsolute() || uri.isOpaque()) {
            return Shape.problems(List.of(NOT_A_URL));
        }
        List<String> problems = new ArrayList<>();
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            problems.add(NOT_HTTPS);
        }
        String authority = uri.getRawAuthority();
        // A registry-based authority (an underscore or a non-ASCII name in the host) parses with no
        // host and no user info, so credentials there are found in the raw authority instead.
        if (uri.getRawUserInfo() != null || (authority != null && authority.contains("@"))) {
            problems.add(USER_INFO);
        }
        if (uri.getRawFragment() != null) {
            problems.add(FRAGMENT);
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            problems.add(NO_HOST);
        } else if (isIpLiteral(host)) {
            problems.add(IP_LITERAL);
        }
        return problems.isEmpty() ? Shape.of(uri) : Shape.problems(List.copyOf(problems));
    }

    private static boolean isIpLiteral(@NonNull String host) {
        return host.startsWith("[")
                || host.indexOf(':') >= 0
                || IPV4_LIKE.matcher(host).matches();
    }

    /**
     * True when {@code address} is publicly routable under the rules above. Package-visible for the
     * address-class tests.
     */
    static boolean isPublic(@NonNull InetAddress address) {
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return false;
        }
        byte[] bytes = address.getAddress();
        return address instanceof Inet4Address ? isPublicV4(bytes) : isPublicV6(bytes);
    }

    private static boolean isPublicV4(byte @NonNull [] b) {
        int o0 = b[0] & 0xff;
        int o1 = b[1] & 0xff;
        int o2 = b[2] & 0xff;
        boolean refused = o0 == 0 // 0.0.0.0/8, "this network"
                || o0 == 10 // 10/8 private
                || o0 == 127 // 127/8 loopback
                || (o0 == 100 && (o1 & 0xc0) == 64) // 100.64/10 carrier-grade NAT
                || (o0 == 169 && o1 == 254) // 169.254/16 link-local, cloud metadata 169.254.169.254
                || (o0 == 172 && (o1 & 0xf0) == 16) // 172.16/12 private
                || (o0 == 192 && o1 == 168) // 192.168/16 private
                || (o0 == 192 && o1 == 0 && (o2 == 0 || o2 == 2)) // 192.0.0/24 IETF, 192.0.2/24 TEST-NET-1
                || (o0 == 198 && (o1 & 0xfe) == 18) // 198.18/15 benchmarking
                || (o0 == 198 && o1 == 51 && o2 == 100) // TEST-NET-2
                || (o0 == 203 && o1 == 0 && o2 == 113) // TEST-NET-3
                || o0 >= 224; // 224/4 multicast, 240/4 reserved, 255.255.255.255 broadcast
        return !refused;
    }

    private static boolean isPublicV6(byte @NonNull [] b) {
        int b0 = b[0] & 0xff;
        int b1 = b[1] & 0xff;
        if ((b0 & 0xfe) == 0xfc // fc00::/7 unique local, incl. fd00:ec2::254
                || (b0 == 0xfe && (b1 & 0x80) == 0x80) // fe80::/10 link-local, fec0::/10 site-local
                || b0 == 0xff) { // ff00::/8 multicast
            return false;
        }
        if (allZero(b, 0, 10)) {
            // ::/80: unspecified, loopback, IPv4-compatible and IPv4-mapped. No public webhook needs them.
            return false;
        }
        if (b0 == 0x01 && b1 == 0x00 && allZero(b, 2, 8)) {
            return false; // 100::/64 discard-only
        }
        if (b0 == 0x20 && b1 == 0x01) {
            int b2 = b[2] & 0xff;
            int b3 = b[3] & 0xff;
            if ((b2 == 0x00 && b3 == 0x00) // 2001::/32 Teredo, which tunnels an obfuscated IPv4 address
                    || (b2 == 0x0d && b3 == 0xb8)) { // 2001:db8::/32 documentation
                return false;
            }
        }
        if (b0 == 0x20 && b1 == 0x02) {
            return isPublicV4(Arrays.copyOfRange(b, 2, 6)); // 2002::/16 6to4 embeds an IPv4 address
        }
        if (b0 == 0x00 && b1 == 0x64 && (b[2] & 0xff) == 0xff && (b[3] & 0xff) == 0x9b) {
            if (allZero(b, 4, 12)) {
                return isPublicV4(Arrays.copyOfRange(b, 12, 16)); // 64:ff9b::/96 NAT64 embeds IPv4
            }
            return false; // 64:ff9b:1::/48 local-use NAT64
        }
        return true;
    }

    private static boolean allZero(byte @NonNull [] bytes, int from, int to) {
        for (int i = from; i < to; i++) {
            if (bytes[i] != 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * The outcome of {@link #validate(String)}: the parsed destination, or why it is refused.
     *
     * @param uri the destination when accepted, else null
     * @param problems one message per refused rule; empty when accepted
     */
    public record Verdict(@Nullable URI uri, @NonNull List<String> problems) {

        static @NonNull Verdict accepted(@NonNull URI uri) {
            return new Verdict(uri, List.of());
        }

        static @NonNull Verdict refused(@NonNull List<String> problems) {
            return new Verdict(null, List.copyOf(problems));
        }

        public boolean accepted() {
            return uri != null && problems.isEmpty();
        }
    }

    private record Shape(@Nullable URI uri, @NonNull List<String> problems) {

        static @NonNull Shape of(@NonNull URI uri) {
            return new Shape(uri, List.of());
        }

        static @NonNull Shape problems(@NonNull List<String> problems) {
            return new Shape(null, problems);
        }
    }

    /** The destination is refused by the rules; nothing was, or will be, sent to it. */
    public static class DestinationRefusedException extends RuntimeException {

        public DestinationRefusedException(@NonNull String reason) {
            super(reason);
        }
    }
}
