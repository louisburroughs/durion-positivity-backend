package com.positivity.documents.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.documents.internal.enums.DocumentFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@Schema(description = "Request to render document content into a target output format")
public class RenderRequest {

    @Schema(
            description = "Target output format for the rendered document",
            example = "JSON",
            requiredMode = REQUIRED)
    @NotNull
    private DocumentFormat format;

    @Schema(
            description = "Optional identifier of the template to apply when rendering",
            example = "invoice-default",
            requiredMode = NOT_REQUIRED)
    private String templateId;

    @Schema(
            description = "Raw content to be rendered into the target format",
            example = "{\"title\":\"Invoice\",\"total\":125.00}",
            requiredMode = REQUIRED)
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
