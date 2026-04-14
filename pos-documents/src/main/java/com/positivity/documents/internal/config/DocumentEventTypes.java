package com.positivity.documents.internal.config;

import com.positivity.events.EventTypeRegistration;
import java.util.List;

public final class DocumentEventTypes {

    private DocumentEventTypes() {}

    public static List<EventTypeRegistration> all() {
        return List.of(EventTypeRegistration.write("DOCUMENT_RENDER", "Render document into PDF format")
                .build());
    }
}
