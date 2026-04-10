package com.positivity.mcp.internal.llm;

import java.util.List;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
@ConditionalOnMissingBean(ChatModelClient.class)
public class NoopChatModelClient implements ChatModelClient {

    @Override
    public @NonNull Mono<ChatCompletion> complete(@NonNull ChatRequest request) {
        return Mono.just(new ChatCompletion(
                "No LLM provider is configured.",
                List.of(),
                true));
    }
}
