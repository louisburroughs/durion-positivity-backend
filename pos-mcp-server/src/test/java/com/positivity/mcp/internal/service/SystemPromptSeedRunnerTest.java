package com.positivity.mcp.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.mcp.internal.entity.SystemPrompt;
import com.positivity.mcp.internal.repository.SystemPromptRepository;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.ApplicationArguments;

@ExtendWith(MockitoExtension.class)
class SystemPromptSeedRunnerTest {

    @Mock
    private SystemPromptRepository systemPromptRepository;

    @Mock
    private ApplicationArguments applicationArguments;

    @Test
    @DisplayName("seedPrompts uses master and domain agent prompt names")
    void seedPrompts_usesMasterAndDomainAgentPromptNames() {
        Map<String, String> seedPrompts = SystemPromptSeedRunner.seedPrompts();

        assertThat(seedPrompts).containsKeys("master", "inventory", "order", "customer", "workorder", "admin");
        assertThat(seedPrompts)
                .doesNotContainKeys(
                        SystemPromptDefaults.ROLE_ADMIN_PROMPT_NAME,
                        SystemPromptDefaults.ROLE_SYSTEM_ADMINISTRATOR_PROMPT_NAME,
                        SystemPromptDefaults.ROLE_SERVICE_ADVISOR_PROMPT_NAME);
        assertThat(seedPrompts.get("shop-manager")).contains("Stay inside shop-manager scope");
    }

    @Test
    @DisplayName("run seeds missing prompts")
    void run_whenPromptMissing_seedsPrompt() {
        Map<String, String> seedPrompts = SystemPromptSeedRunner.seedPrompts();
        when(systemPromptRepository.findByName(anyString())).thenReturn(Optional.empty());
        when(systemPromptRepository.save(any(SystemPrompt.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var runner = new SystemPromptSeedRunner(systemPromptRepository);
        runner.run(applicationArguments);

        ArgumentCaptor<SystemPrompt> promptCaptor = ArgumentCaptor.forClass(SystemPrompt.class);
        verify(systemPromptRepository, org.mockito.Mockito.times(seedPrompts.size()))
                .save(promptCaptor.capture());
        assertThat(promptCaptor.getAllValues())
                .extracting(SystemPrompt::getName)
                .containsExactlyInAnyOrderElementsOf(seedPrompts.keySet());
    }

    @Test
    @DisplayName("run updates prompt when DB content differs from code source of truth")
    void run_whenPromptExistsWithDifferentContent_updatesPrompt() {
        Map<String, String> seedPrompts = SystemPromptSeedRunner.seedPrompts();
        Map<String, SystemPrompt> existingByName = existingPromptsWithSameContent(seedPrompts);
        existingByName.get("master").setContent("stale master content");

        when(systemPromptRepository.findByName(anyString()))
                .thenAnswer(invocation -> Optional.ofNullable(existingByName.get(invocation.getArgument(0))));
        when(systemPromptRepository.save(any(SystemPrompt.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var runner = new SystemPromptSeedRunner(systemPromptRepository);
        runner.run(applicationArguments);

        ArgumentCaptor<SystemPrompt> promptCaptor = ArgumentCaptor.forClass(SystemPrompt.class);
        verify(systemPromptRepository).save(promptCaptor.capture());
        assertThat(promptCaptor.getValue().getName()).isEqualTo("master");
        assertThat(promptCaptor.getValue().getContent()).isEqualTo(seedPrompts.get("master"));
    }

    @Test
    @DisplayName("run does not update prompts when DB content already matches code")
    void run_whenPromptExistsWithSameContent_doesNotSave() {
        Map<String, String> seedPrompts = SystemPromptSeedRunner.seedPrompts();
        Map<String, SystemPrompt> existingByName = existingPromptsWithSameContent(seedPrompts);

        when(systemPromptRepository.findByName(anyString()))
                .thenAnswer(invocation -> Optional.ofNullable(existingByName.get(invocation.getArgument(0))));

        var runner = new SystemPromptSeedRunner(systemPromptRepository);
        runner.run(applicationArguments);

        verify(systemPromptRepository, never()).save(any(SystemPrompt.class));
    }

    private static Map<String, SystemPrompt> existingPromptsWithSameContent(Map<String, String> seedPrompts) {
        Map<String, SystemPrompt> prompts = new HashMap<>();
        for (Map.Entry<String, String> entry : seedPrompts.entrySet()) {
            var prompt = new SystemPrompt();
            prompt.setName(entry.getKey());
            prompt.setContent(entry.getValue());
            prompts.put(entry.getKey(), prompt);
        }
        return prompts;
    }
}
