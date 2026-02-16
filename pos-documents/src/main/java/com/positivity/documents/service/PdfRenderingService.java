package com.positivity.documents.service;

import com.positivity.documents.internal.dto.RenderRequest;
import org.jspecify.annotations.NonNull;

public interface PdfRenderingService {

    @NonNull
    byte[] renderPdf(@NonNull RenderRequest request);
}
