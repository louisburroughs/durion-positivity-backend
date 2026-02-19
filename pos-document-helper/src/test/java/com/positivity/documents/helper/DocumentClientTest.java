package com.positivity.documents.helper;

import com.positivity.documents.helper.exception.DocumentRenderException;
import com.positivity.documents.helper.exception.TemplateRegistrationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for DocumentClient with error handling scenarios.
 * 
 * Issue: ADR-0020
 */
@ExtendWith(MockitoExtension.class)
class DocumentClientTest {
    
    @Mock
    private RestClient restClient;
    
    @Mock
    private RestClient.RequestBodyUriSpec requestBodyUriSpec;
    
    @Mock
    private RestClient.RequestBodySpec requestBodySpec;
    
    @Mock
    private RestClient.ResponseSpec responseSpec;
    
    private DocumentClient documentClient;
    
    @BeforeEach
    void setUp() {
        documentClient = new DocumentClient(restClient, "http://pos-documents:8080");
    }
    
    @Test
    void shouldRegisterTemplate() {
        // Given
        TemplateRegistration registration = TemplateRegistration.builder()
                .templateId("TEST_TEMPLATE")
                .content("<html><body>Test</body></html>")
                .version("1.0.0")
                .description("Test template")
                .owner("pos-test")
                .build();
        
        when(restClient.put()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(anyString(), any(Object[].class))).thenReturn(requestBodySpec);
        when(requestBodySpec.contentType(any())).thenReturn(requestBodySpec);
        when(requestBodySpec.body(any())).thenReturn(requestBodySpec);
        when(requestBodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.toBodilessEntity()).thenReturn(ResponseEntity.ok().build());
        
        // When/Then
        assertThatNoException().isThrownBy(() -> documentClient.registerTemplate(registration));
    }
    
    @Test
    void shouldThrowExceptionWhenTemplateRegistrationFails() {
        // Given
        TemplateRegistration registration = TemplateRegistration.builder()
                .templateId("TEST_TEMPLATE")
                .content("<html><body>Test</body></html>")
                .version("1.0.0")
                .description("Test template")
                .owner("pos-test")
                .build();
        
        when(restClient.put()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(anyString(), any(Object[].class))).thenReturn(requestBodySpec);
        when(requestBodySpec.contentType(any())).thenReturn(requestBodySpec);
        when(requestBodySpec.body(any())).thenReturn(requestBodySpec);
        when(requestBodySpec.retrieve()).thenThrow(new RestClientException("Connection refused"));
        
        // When/Then
        assertThatThrownBy(() -> documentClient.registerTemplate(registration))
                .isInstanceOf(TemplateRegistrationException.class)
                .hasMessageContaining("TEST_TEMPLATE")
                .hasMessageContaining("HTTP PUT failed");
    }
    
    @Test
    void shouldRenderDocument() {
        // Given
        RenderRequest request = RenderRequest.builder()
                .format(DocumentFormat.PDF)
                .templateId("TEST_TEMPLATE")
                .content("{\"test\": \"data\"}")
                .build();
        
        byte[] expectedPdf = new byte[]{1, 2, 3, 4, 5};
        
        when(restClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.contentType(any())).thenReturn(requestBodySpec);
        when(requestBodySpec.body(any())).thenReturn(requestBodySpec);
        when(requestBodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.toEntity(byte[].class))
                .thenReturn(ResponseEntity.ok(expectedPdf));
        
        // When
        byte[] result = documentClient.renderDocument(request);
        
        // Then
        assertThat(result).isEqualTo(expectedPdf);
    }
    
    @Test
    void shouldThrowExceptionWhenRenderingFails() {
        // Given
        RenderRequest request = RenderRequest.builder()
                .format(DocumentFormat.PDF)
                .templateId("TEST_TEMPLATE")
                .content("{\"test\": \"data\"}")
                .build();
        
        when(restClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.contentType(any())).thenReturn(requestBodySpec);
        when(requestBodySpec.body(any())).thenReturn(requestBodySpec);
        when(requestBodySpec.retrieve()).thenThrow(new RestClientException("Service unavailable"));
        
        // When/Then
        assertThatThrownBy(() -> documentClient.renderDocument(request))
                .isInstanceOf(DocumentRenderException.class)
                .hasMessageContaining("TEST_TEMPLATE")
                .hasMessageContaining("HTTP POST failed");
    }
    
    @Test
    void shouldThrowExceptionWhenRenderResponseIsNotOk() {
        // Given
        RenderRequest request = RenderRequest.builder()
                .format(DocumentFormat.PDF)
                .templateId("TEST_TEMPLATE")
                .content("{\"test\": \"data\"}")
                .build();
        
        when(restClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.contentType(any())).thenReturn(requestBodySpec);
        when(requestBodySpec.body(any())).thenReturn(requestBodySpec);
        when(requestBodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.toEntity(byte[].class))
                .thenReturn(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build());
        
        // When/Then
        assertThatThrownBy(() -> documentClient.renderDocument(request))
                .isInstanceOf(DocumentRenderException.class)
                .hasMessageContaining("TEST_TEMPLATE")
                .hasMessageContaining("Unexpected response status");
    }
    
    @Test
    void shouldRejectNullRegistration() {
        assertThatThrownBy(() -> documentClient.registerTemplate(null))
                .isInstanceOf(NullPointerException.class);
    }
    
    @Test
    void shouldRejectNullRenderRequest() {
        assertThatThrownBy(() -> documentClient.renderDocument(null))
                .isInstanceOf(NullPointerException.class);
    }
}
