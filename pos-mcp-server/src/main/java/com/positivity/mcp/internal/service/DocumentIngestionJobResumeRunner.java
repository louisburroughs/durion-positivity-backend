package com.positivity.mcp.internal.service;

import org.jspecify.annotations.NonNull;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public class DocumentIngestionJobResumeRunner implements ApplicationRunner {

    private final DocumentIngestionServiceImpl documentIngestionService;

    public DocumentIngestionJobResumeRunner(@NonNull DocumentIngestionServiceImpl documentIngestionService) {
        this.documentIngestionService = documentIngestionService;
    }

    @Override
    public void run(ApplicationArguments args) {
        documentIngestionService.resumeIncompleteJobs();
    }
}
