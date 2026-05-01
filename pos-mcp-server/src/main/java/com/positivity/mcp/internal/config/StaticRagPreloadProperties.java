package com.positivity.mcp.internal.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "mcp.rag.preload")
public record StaticRagPreloadProperties(List<StaticDocEntry> docs) {

    public record StaticDocEntry(String id, String sourcePath) {}
}