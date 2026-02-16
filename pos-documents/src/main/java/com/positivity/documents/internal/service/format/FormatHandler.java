package com.positivity.documents.internal.service.format;

import com.positivity.documents.internal.enums.DocumentFormat;
import java.util.Map;

public interface FormatHandler {

    String processContent(String rawContent, Map<String, Object> context);

    DocumentFormat supportedFormat();
}
