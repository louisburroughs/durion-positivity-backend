package com.positivity.documents.internal.service;

import com.positivity.documents.internal.config.PdfConfiguration;
import com.positivity.documents.internal.exception.TemplateNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class TemplateService {

    public static final String DEFAULT_TEMPLATE_ID = "DEFAULT_STANDARD_TEMPLATE";

    private static final Logger log = LoggerFactory.getLogger(TemplateService.class);
    private static final Pattern VARIABLE_PATTERN = Pattern.compile("\\$\\{([a-zA-Z0-9_.]+)}");
    private static final Pattern EACH_PATTERN = Pattern.compile("\\{\\{#each\\s+([a-zA-Z0-9_.]+)}}([\\s\\S]*?)\\{\\{/each}}", Pattern.MULTILINE);
    private static final Pattern IF_PATTERN = Pattern.compile("\\{\\{#if\\s+([a-zA-Z0-9_.]+)}}([\\s\\S]*?)\\{\\{/if}}", Pattern.MULTILINE);
    private static final Pattern TEMPLATE_ID_PATTERN = Pattern.compile("[A-Z0-9_-]+");

    private final ResourceLoader resourceLoader;
    private final String templateBasePath;
    private final Map<String, String> templateCache = new ConcurrentHashMap<>();

    public TemplateService(
            ResourceLoader resourceLoader,
            PdfConfiguration pdfConfiguration) {
        this.resourceLoader = resourceLoader;
        this.templateBasePath = pdfConfiguration.templateBasePath();
    }

    public String resolveTemplate(String templateId) {
        String normalized = (templateId == null || templateId.isBlank())
                ? DEFAULT_TEMPLATE_ID
                : templateId.trim();

        if (!TEMPLATE_ID_PATTERN.matcher(normalized).matches()) {
            throw new TemplateNotFoundException("Template not found: " + normalized);
        }

        return templateCache.computeIfAbsent(normalized, this::loadTemplate);
    }

    public String bindTemplate(String template, Map<String, Object> context) {
        String rendered = applyEachBlocks(template, context);
        rendered = applyIfBlocks(rendered, context);

        Matcher variableMatcher = VARIABLE_PATTERN.matcher(rendered);
        StringBuilder output = new StringBuilder();
        while (variableMatcher.find()) {
            String key = variableMatcher.group(1);
            Object value = resolvePath(context, key);
            variableMatcher.appendReplacement(output, Matcher.quoteReplacement(value == null ? "" : String.valueOf(value)));
        }
        variableMatcher.appendTail(output);
        return output.toString();
    }

    private String applyEachBlocks(String template, Map<String, Object> context) {
        Matcher matcher = EACH_PATTERN.matcher(template);
        StringBuilder output = new StringBuilder();
        while (matcher.find()) {
            String listPath = matcher.group(1);
            String block = matcher.group(2);
            Object value = resolvePath(context, listPath);

            String replacement = "";
            if (value instanceof Collection<?> collection) {
                StringBuilder replacementBuilder = new StringBuilder();
                for (Object item : collection) {
                    String blockRendered = block.replace("${this}", Objects.toString(item, ""));
                    if (item instanceof Map<?, ?> mapItem) {
                        for (Map.Entry<?, ?> entry : mapItem.entrySet()) {
                            String token = "${this." + entry.getKey() + "}";
                            blockRendered = blockRendered.replace(token, Objects.toString(entry.getValue(), ""));
                        }
                    }
                    replacementBuilder.append(blockRendered);
                }
                replacement = replacementBuilder.toString();
            }

            matcher.appendReplacement(output, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(output);
        return output.toString();
    }

    private String applyIfBlocks(String template, Map<String, Object> context) {
        Matcher matcher = IF_PATTERN.matcher(template);
        StringBuilder output = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1);
            String block = matcher.group(2);
            Object value = resolvePath(context, key);
            boolean truthy = value instanceof Boolean boolValue
                    ? boolValue
                    : value != null && !String.valueOf(value).isBlank();
            matcher.appendReplacement(output, Matcher.quoteReplacement(truthy ? block : ""));
        }
        matcher.appendTail(output);
        return output.toString();
    }

    private Object resolvePath(Map<String, Object> context, String path) {
        String[] pathParts = path.split("\\.");
        Object current = context;
        for (String part : pathParts) {
            if (!(current instanceof Map<?, ?> currentMap)) {
                return null;
            }
            current = currentMap.get(part);
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    private String loadTemplate(String templateId) {
        String normalizedBasePath = templateBasePath.endsWith("/")
            ? templateBasePath.substring(0, templateBasePath.length() - 1)
            : templateBasePath;
        Resource resource = resourceLoader.getResource(normalizedBasePath + "/" + templateId + ".html");
        if (!resource.exists()) {
            throw new TemplateNotFoundException("Template not found: " + templateId);
        }
        try {
            log.debug("Loading template {} from classpath", templateId);
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new TemplateNotFoundException("Failed to load template: " + templateId);
        }
    }
}
