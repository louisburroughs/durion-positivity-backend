package com.positivity.documents.internal.dto;

import com.positivity.documents.internal.enums.DocumentFormat;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public class RenderRequest {

    @NotNull
    private DocumentFormat format;

    private String templateId;

    @NotBlank
    private String content;

    public DocumentFormat getFormat() {
        return format;
    }

    public void setFormat(DocumentFormat format) {
        this.format = format;
    }

    public String getTemplateId() {
        return templateId;
    }

    public void setTemplateId(String templateId) {
        this.templateId = templateId;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }
}
