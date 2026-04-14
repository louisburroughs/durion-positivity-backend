package com.positivity.mcp.internal.config;

import com.positivity.events.EventTypeRegistration;
import java.util.List;

/**
 * Registry of all event types emitted by the pos-mcp-server module.
 */
public final class EventTypes {

    private EventTypes() {
        // Utility class
    }

    public static List<EventTypeRegistration> all() {
        return List.of(
                EventTypeRegistration.search("MCP_CHAT_EXECUTE", "Execute an MCP tool through the chat endpoint")
                        .build(),
                EventTypeRegistration.write("MCP_DOCUMENT_INGEST", "Ingest a document into the RAG vector store")
                        .build(),
                EventTypeRegistration.write("MCP_CHAT_STREAM_EXECUTE", "Execute a streaming MCP chat request")
                        .build(),
                EventTypeRegistration.write("MCP_LLM_API_CREATE", "Create an LLM API configuration")
                        .build(),
                EventTypeRegistration.write("MCP_LLM_API_UPDATE", "Update an LLM API configuration")
                        .build(),
                EventTypeRegistration.write("MCP_LLM_API_DELETE", "Delete an LLM API configuration")
                        .build(),
                EventTypeRegistration.write("MCP_SYSTEM_PROMPT_CREATE", "Create a system prompt")
                        .build(),
                EventTypeRegistration.write("MCP_SYSTEM_PROMPT_UPDATE", "Update a system prompt")
                        .build(),
                EventTypeRegistration.write("MCP_SYSTEM_PROMPT_DELETE", "Delete a system prompt")
                        .build(),
                EventTypeRegistration.write("NLTI_REQUEST_SUBMIT", "Submit a NLTI natural language request")
                        .build(),
                EventTypeRegistration.fastRead("NLTI_AUDIT_QUERY", "Query the NLTI audit event ledger")
                        .build());
    }
}
