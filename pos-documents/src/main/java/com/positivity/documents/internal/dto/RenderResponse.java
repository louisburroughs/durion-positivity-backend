package com.positivity.documents.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(description = "Result of rendering document content into a target output format")
public class RenderResponse {

    @Schema(description = "Output format that was produced", example = "JSON", requiredMode = REQUIRED)
    @NotNull
    private String format;

    @Schema(description = "Size of the rendered output in bytes", example = "2048", requiredMode = REQUIRED)
    private int bytes;

    public RenderResponse() {}

    public RenderResponse(String format, int bytes) {
        this.format = format;
        this.bytes = bytes;
    }

    public String getFormat() {
        return format;
    }

    public void setFormat(String format) {
        this.format = format;
    }

    public int getBytes() {
        return bytes;
    }

    public void setBytes(int bytes) {
        this.bytes = bytes;
    }
}
