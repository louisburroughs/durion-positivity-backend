package com.positivity.mcp.internal.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.URI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

class BearerTokenRelayInterceptorTest {

  private BearerTokenRelayInterceptor interceptor;

  @BeforeEach
  void setUp() {
    interceptor = new BearerTokenRelayInterceptor();
    RequestContextHolder.resetRequestAttributes();
  }

  @AfterEach
  void tearDown() {
    RequestContextHolder.resetRequestAttributes();
  }

  @Test
  @DisplayName("intercept forwards Authorization header when Bearer token present in context")
  void intercept_forwardsBearerToken_whenPresentInServletContext() throws IOException {
    MockHttpServletRequest httpRequest = new MockHttpServletRequest();
    httpRequest.addHeader("Authorization", "Bearer test-token");
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(httpRequest));

    MockClientHttpRequest outboundRequest = new MockClientHttpRequest(HttpMethod.GET,
        URI.create("http://test/endpoint"));
    ClientHttpRequestExecution execution = mock(ClientHttpRequestExecution.class);
    ClientHttpResponse mockResponse = mock(ClientHttpResponse.class);
    when(execution.execute(outboundRequest, new byte[0])).thenReturn(mockResponse);

    interceptor.intercept(outboundRequest, new byte[0], execution);

    assertThat(outboundRequest.getHeaders().getFirst("Authorization")).isEqualTo("Bearer test-token");
  }

  @Test
  @DisplayName("intercept does not add Authorization header when no Bearer token in context")
  void intercept_doesNotAddHeader_whenNoBearerTokenInContext() throws IOException {
    MockHttpServletRequest httpRequest = new MockHttpServletRequest();
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(httpRequest));

    MockClientHttpRequest outboundRequest = new MockClientHttpRequest(HttpMethod.GET,
        URI.create("http://test/endpoint"));
    ClientHttpRequestExecution execution = mock(ClientHttpRequestExecution.class);
    ClientHttpResponse mockResponse = mock(ClientHttpResponse.class);
    when(execution.execute(outboundRequest, new byte[0])).thenReturn(mockResponse);

    interceptor.intercept(outboundRequest, new byte[0], execution);

    assertThat(outboundRequest.getHeaders().get("Authorization")).isNullOrEmpty();
  }

  @Test
  @DisplayName("intercept gracefully skips relay when no request context available")
  void intercept_skipsRelay_whenNoRequestContext() throws IOException {
    MockClientHttpRequest outboundRequest = new MockClientHttpRequest(HttpMethod.GET,
        URI.create("http://test/endpoint"));
    ClientHttpRequestExecution execution = mock(ClientHttpRequestExecution.class);
    ClientHttpResponse mockResponse = mock(ClientHttpResponse.class);
    when(execution.execute(outboundRequest, new byte[0])).thenReturn(mockResponse);

    interceptor.intercept(outboundRequest, new byte[0], execution);

    assertThat(outboundRequest.getHeaders().get("Authorization")).isNullOrEmpty();
  }

  @Test
  @DisplayName("intercept does not overwrite outbound Authorization header when it uses a non-Bearer scheme")
  void intercept_doesNotOverwrite_whenOutboundAuthHeaderIsNonBearer() throws IOException {
    MockHttpServletRequest httpRequest = new MockHttpServletRequest();
    httpRequest.addHeader("Authorization", "Bearer incoming-token");
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(httpRequest));

    MockClientHttpRequest outboundRequest = new MockClientHttpRequest(HttpMethod.GET,
        URI.create("http://test/endpoint"));
    outboundRequest.getHeaders().set("Authorization", "Basic dXNlcjpwYXNz");
    ClientHttpRequestExecution execution = mock(ClientHttpRequestExecution.class);
    ClientHttpResponse mockResponse = mock(ClientHttpResponse.class);
    when(execution.execute(outboundRequest, new byte[0])).thenReturn(mockResponse);

    interceptor.intercept(outboundRequest, new byte[0], execution);

    assertThat(outboundRequest.getHeaders().getFirst("Authorization")).isEqualTo("Basic dXNlcjpwYXNz");
  }

  @Test
  @DisplayName("intercept overwrites outbound Bearer Authorization header with incoming session Bearer token")
  void intercept_overwritesOutboundBearer_whenBothAreBearer() throws IOException {
    MockHttpServletRequest httpRequest = new MockHttpServletRequest();
    httpRequest.addHeader("Authorization", "Bearer user-session-token");
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(httpRequest));

    MockClientHttpRequest outboundRequest = new MockClientHttpRequest(HttpMethod.GET,
        URI.create("http://test/endpoint"));
    outboundRequest.getHeaders().set("Authorization", "Bearer existing-service-token");
    ClientHttpRequestExecution execution = mock(ClientHttpRequestExecution.class);
    ClientHttpResponse mockResponse = mock(ClientHttpResponse.class);
    when(execution.execute(outboundRequest, new byte[0])).thenReturn(mockResponse);

    interceptor.intercept(outboundRequest, new byte[0], execution);

    assertThat(outboundRequest.getHeaders().getFirst("Authorization")).isEqualTo("Bearer user-session-token");
  }
}
