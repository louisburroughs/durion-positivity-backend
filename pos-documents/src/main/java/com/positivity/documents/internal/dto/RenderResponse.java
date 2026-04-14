package com.positivity.documents.internal.dto;

public class RenderResponse {

    private String format;
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
