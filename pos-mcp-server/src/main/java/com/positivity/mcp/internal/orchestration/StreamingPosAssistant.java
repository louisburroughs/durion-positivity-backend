package com.positivity.mcp.internal.orchestration;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

public interface StreamingPosAssistant {

  @SystemMessage("""
      You are a POS assistant for Durion Positivity.
      Use the provided tools to answer questions about inventory, orders,
      customers, pricing, and other POS operations.
      Always verify information using tools before answering.
      If you cannot find the answer, say so clearly.
      Never fabricate data.
      {{roleContext}}
      """)
  TokenStream chat(@UserMessage String userMessage, @V("roleContext") String roleContext);
}