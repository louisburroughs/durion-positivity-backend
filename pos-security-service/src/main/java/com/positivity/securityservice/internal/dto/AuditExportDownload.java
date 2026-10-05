package com.positivity.securityservice.internal.dto;

/**
 * The file of a completed audit export, as the download endpoint streams it (#2408).
 *
 * @param fileName attachment file name
 * @param contentType {@code text/csv} or {@code application/json}
 * @param content the UTF-8 encoded file
 */
public record AuditExportDownload(String fileName, String contentType, byte[] content) {}
