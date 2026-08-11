package com.positivity.marketing.internal.service;

import com.positivity.marketing.internal.domain.TemplateToken;
import com.positivity.marketing.internal.enums.AudienceType;
import com.positivity.marketing.internal.enums.CampaignChannel;
import com.positivity.marketing.internal.exception.MarketingUnprocessableEntityException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Validates and renders {@code {{token}}} substitution in message templates (Story #1147).
 *
 * <p>Rendering is plain textual substitution with no expression language and no reflection:
 * templates are author-supplied data, and a template engine capable of evaluating expressions
 * would make a marketer's text box an execution surface.
 */
@Component
public class TemplateRenderService {

    private static final Pattern TOKEN = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_]+)\\s*}}");

    /**
     * A single SMS segment is 160 GSM-7 characters; beyond that a carrier splits the message and
     * bills per part. Templates near the limit are rejected rather than silently multiplying the
     * cost of a campaign by the size of its audience.
     */
    private static final int SMS_MAX_LENGTH = 320;

    /**
     * Rejects unknown tokens, and tokens that exist but are not available to this audience —
     * {@code accountName} in an individual template would render blank for every recipient.
     */
    public void validate(
            @NonNull CampaignChannel channel,
            @NonNull AudienceType audienceType,
            @Nullable String subject,
            @NonNull String body) {
        List<String> problems = new ArrayList<>();
        if (subject != null) {
            problems.addAll(unknownTokens(subject, audienceType));
        }
        problems.addAll(unknownTokens(body, audienceType));
        if (!problems.isEmpty()) {
            throw new MarketingUnprocessableEntityException("Template uses tokens that are not valid for a "
                    + audienceType + " audience: " + String.join(", ", problems)
                    + ". Available tokens: " + TemplateToken.namesFor(audienceType));
        }
        if (channel == CampaignChannel.SMS) {
            if (subject != null && !subject.isBlank()) {
                throw new MarketingUnprocessableEntityException("SMS templates have no subject line");
            }
            if (body.length() > SMS_MAX_LENGTH) {
                throw new MarketingUnprocessableEntityException("SMS body is " + body.length()
                        + " characters, over the " + SMS_MAX_LENGTH
                        + "-character limit; carriers bill each additional segment per recipient");
            }
        } else if (subject == null || subject.isBlank()) {
            throw new MarketingUnprocessableEntityException("Email templates require a subject line");
        }
    }

    /**
     * Substitute known tokens. A token with no supplied value renders empty rather than leaving
     * the literal {@code {{token}}} in the message — a blank reads as a formatting slip, the raw
     * token reads as a broken system.
     */
    public @NonNull String render(@NonNull String template, @NonNull Map<String, String> values) {
        Matcher matcher = TOKEN.matcher(template);
        StringBuilder rendered = new StringBuilder();
        while (matcher.find()) {
            String replacement = values.getOrDefault(matcher.group(1), "");
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(rendered);
        return rendered.toString();
    }

    private List<String> unknownTokens(String text, AudienceType audienceType) {
        List<String> problems = new ArrayList<>();
        Matcher matcher = TOKEN.matcher(text);
        while (matcher.find()) {
            String name = matcher.group(1);
            boolean usable = TemplateToken.fromName(name)
                    .filter(token -> token.availableFor(audienceType))
                    .isPresent();
            if (!usable) {
                problems.add(name);
            }
        }
        return problems;
    }
}
