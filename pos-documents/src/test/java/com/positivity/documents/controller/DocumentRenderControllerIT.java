package com.positivity.documents.controller;

import com.positivity.documents.internal.dto.RenderRequest;
import com.positivity.documents.internal.enums.DocumentFormat;
import com.positivity.documents.service.PdfRenderingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
class DocumentRenderControllerIT {

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private PdfRenderingService pdfRenderingService;

    @BeforeEach
    void setup() {
        this.mockMvc = webAppContextSetup(context).build();
    }

    @Test
    void shouldReturnPdfContentType() throws Exception {
        when(pdfRenderingService.renderPdf(any())).thenReturn("%PDF-test".getBytes());

        RenderRequest request = new RenderRequest();
        request.setFormat(DocumentFormat.TEXT);
        request.setContent("hello");

        mockMvc.perform(post("/api/documents/render")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", MediaType.APPLICATION_PDF_VALUE));
    }
}
