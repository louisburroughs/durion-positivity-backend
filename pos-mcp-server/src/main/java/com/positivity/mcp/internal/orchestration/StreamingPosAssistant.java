package com.positivity.mcp.internal.orchestration;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

public interface StreamingPosAssistant {

  @SystemMessage("""
      You are a POS assistant for Positivity.
      Use the provided tools when answering questions that require current
      inventory, order, customer, pricing, or other POS business data.
      For general conversation, answer directly without using tools.
      If you cannot find the answer, say so clearly.
      Never fabricate data.
      {{roleContext}}
      """)
  TokenStream chat(@MemoryId String memoryId, @UserMessage String userMessage, @V("roleContext") String roleContext);
}
