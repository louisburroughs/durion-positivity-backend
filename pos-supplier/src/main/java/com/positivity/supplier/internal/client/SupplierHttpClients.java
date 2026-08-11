package com.positivity.supplier.internal.client;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.jspecify.annotations.NonNull;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Vendor-facing {@link RestClient} instances, cached by their timeout pair.
 *
 * <h2>Why this is a bean and not a private map on {@code SupplierBaseClient}</h2>
 *
 * It was a private map, and the OAuth2 token leg therefore could not use it: the token strategy is a
 * collaborator of the base client, so it cannot depend on it back. The token leg instead used the platform
 * {@code RestClient.Builder} from {@code RestClientConfig} — the builder meant for calling
 * pos-event-receiver and pos-security-service — which meant the request that fetches a vendor credential was
 * built on a completely different transport from the request that uses it. Two concrete consequences:
 *
 * <ul>
 *   <li><strong>2s connect / 5s read.</strong> Platform budgets for a service on the same network, applied to
 *       a third-party token endpoint across the internet. A vendor that habitually takes six seconds to mint
 *       a token would fail every exchange, and the profile's own generous timeouts would not apply.
 *   <li><strong>{@code SimpleClientHttpRequestFactory}.</strong> That wraps {@code HttpURLConnection}, which
 *       reports connect and read timeouts as the same {@link java.net.SocketTimeoutException} separable only
 *       by message text. {@code java.net.http.HttpClient} raises {@code HttpConnectTimeoutException} as a
 *       distinct type. One transport for both legs also means redirects are refused on the token leg, which
 *       {@code SimpleClientHttpRequestFactory} followed by default — a redirected token request would have
 *       replayed client credentials to whatever host the {@code Location} header named.
 * </ul>
 *
 * <p><strong>What the token leg does NOT need from this, contrary to an earlier version of this javadoc:</strong>
 * retry classification. That version claimed the distinction mattered because a read timeout on the token
 * request may have minted a token server-side and must therefore not be retried. It does not, and the code
 * never behaved that way: {@code OAuth2ClientCredentialsAuthStrategy} maps every {@code RestClientException}
 * to a {@code SupplierAuthTransportException}, which the base client classifies as
 * {@code PRE_SEND_FAILURE} — retryable — regardless of which timeout occurred. That is the right behaviour and
 * is the call already accepted: a token leg failure means nothing reached the vendor's <em>business</em>
 * endpoint, so no order was placed and no stock was reserved. A wasted token is not a duplicate submission.
 * The doc was wrong, not the code.
 *
 * <p>Both legs now share this factory, so a transport decision made once holds for both.
 *
 * <h2>Cached by timeouts, not by binding</h2>
 *
 * The key is the timeout pair rather than the binding or profile, because that is the only part of the
 * configuration the client object actually embodies. Two profiles with identical timeouts can safely share
 * one client and its connection pool; a per-binding cache would multiply pools for no benefit and leak an
 * entry per binding.
 */
@Component
public class SupplierHttpClients {

    private final ConcurrentMap<String, RestClient> clientsByTimeouts = new ConcurrentHashMap<>();

    /**
     * A client with the given timeouts, created once and reused.
     *
     * @param connectTimeoutMs TCP connect budget
     * @param readTimeoutMs response-read budget
     * @return the cached client for that pair
     */
    @NonNull
    public RestClient forTimeouts(int connectTimeoutMs, int readTimeoutMs) {
        return clientsByTimeouts.computeIfAbsent(connectTimeoutMs + ":" + readTimeoutMs, key -> {
            // JdkClientHttpRequestFactory, NOT the SimpleClientHttpRequestFactory used elsewhere in
            // the fleet. This module's correctness rests on telling a connect timeout (never
            // transmitted, safe to retry) from a read timeout (may have been transmitted, must NOT
            // be retried), and SimpleClientHttpRequestFactory wraps HttpURLConnection, which
            // reports both as java.net.SocketTimeoutException separable only by message text.
            // java.net.http.HttpClient raises HttpConnectTimeoutException as a distinct type, so
            // the classification is type-safe. It also pools connections, which HttpURLConnection
            // does not, and this transport makes repeated calls per vendor.
            HttpClient httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                    // Explicit: never follow a vendor redirect. Silently re-sending a state-creating
                    // POST body to another host is exactly the duplicate-submission hazard this
                    // transport must not have, and a 3xx from a configured baseUrl means OUR
                    // configuration points somewhere that is not the API.
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();
            JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
            factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
            return RestClient.builder().requestFactory(factory).build();
        });
    }
}
