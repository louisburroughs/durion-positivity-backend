package com.positivity.mcp.internal.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

class McpServerConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(McpServerConfiguration.class, BearerTokenRelayInterceptor.class);

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void exposesPlainAndLoadBalancedBuilders_andRelaysBearerTokenOnLoadBalancedBuilder() {
        contextRunner.run(context -> {
            assertThat(context).hasBean("restClientBuilder");
            assertThat(context).hasBean("loadBalancedRestClientBuilder");

            RestClient.Builder primaryBuilder = context.getBean(RestClient.Builder.class);
            RestClient.Builder plainBuilder = context.getBean("restClientBuilder", RestClient.Builder.class);
            RestClient.Builder loadBalancedBuilder =
                    context.getBean("loadBalancedRestClientBuilder", RestClient.Builder.class);

            assertThat(primaryBuilder).isSameAs(plainBuilder);
            assertThat(loadBalancedBuilder).isNotSameAs(plainBuilder);

            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader("Authorization", "Bearer relayed-token");
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

            MockRestServiceServer server =
                    MockRestServiceServer.bindTo(loadBalancedBuilder).build();
            server.expect(requestTo("http://api-gateway/test"))
                    .andExpect(header("Authorization", "Bearer relayed-token"))
                    .andRespond(withSuccess());

            loadBalancedBuilder
                    .baseUrl("http://api-gateway")
                    .build()
                    .get()
                    .uri("/test")
                    .retrieve()
                    .toBodilessEntity();

            server.verify();
        });
    }

    @Test
    @DisplayName("loadBalancedRestClientBuilder carries a request factory bound to ToolHttpProperties (#1660)")
    void loadBalancedRestClientBuilder_appliesConfiguredConnectAndReadTimeouts() {
        contextRunner
                .withPropertyValues("pos.tools.http.connect-timeout=1500ms", "pos.tools.http.read-timeout=20s")
                .run(context -> {
                    RestClient.Builder loadBalancedBuilder =
                            context.getBean("loadBalancedRestClientBuilder", RestClient.Builder.class);

                    ClientHttpRequestFactory requestFactory = (ClientHttpRequestFactory)
                            ReflectionTestUtils.getField(loadBalancedBuilder, "requestFactory");

                    assertThat(requestFactory).isInstanceOf(SimpleClientHttpRequestFactory.class);
                    assertThat((Integer) ReflectionTestUtils.getField(requestFactory, "connectTimeout"))
                            .isEqualTo((int) Duration.ofMillis(1500).toMillis());
                    assertThat((Integer) ReflectionTestUtils.getField(requestFactory, "readTimeout"))
                            .isEqualTo((int) Duration.ofSeconds(20).toMillis());
                });
    }

    @Test
    @DisplayName("loadBalancedRestClientBuilder defaults to 2s connect / 30s read when unconfigured (#1660)")
    void loadBalancedRestClientBuilder_defaultsToTwoAndThirtySeconds() {
        contextRunner.run(context -> {
            RestClient.Builder loadBalancedBuilder =
                    context.getBean("loadBalancedRestClientBuilder", RestClient.Builder.class);

            ClientHttpRequestFactory requestFactory =
                    (ClientHttpRequestFactory) ReflectionTestUtils.getField(loadBalancedBuilder, "requestFactory");

            assertThat(requestFactory).isInstanceOf(SimpleClientHttpRequestFactory.class);
            assertThat((Integer) ReflectionTestUtils.getField(requestFactory, "connectTimeout"))
                    .isEqualTo((int) Duration.ofSeconds(2).toMillis());
            assertThat((Integer) ReflectionTestUtils.getField(requestFactory, "readTimeout"))
                    .isEqualTo((int) Duration.ofSeconds(30).toMillis());
        });
    }
}
