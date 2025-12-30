package com.pos.agent.story.loop;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Context for tracking processing state and detecting loops.
 * Maintains rewrite iteration counts, scenario counts, and inference flags.
 * 
 * Requirements: 11.1, 11.2, 11.3, 11.4, 11.5, 11.6, 11.7
 */
public class ProcessingContext {
    private final Map<String, Integer> sectionRewriteCounts;
    private int acceptanceCriteriaCount;
    private int openQuestionsCount;
    private boolean requiresLegalInference;
    private boolean requiresFinancialInference;
    private boolean requiresSecurityInference;
    private boolean requiresRegulatoryInference;

    public ProcessingContext() {
        this.sectionRewriteCounts = new HashMap<>();
        this.acceptanceCriteriaCount = 0;
        this.openQuestionsCount = 0;
        this.requiresLegalInference = false;
        this.requiresFinancialInference = false;
        this.requiresSecurityInference = false;
        this.requiresRegulatoryInference = false;
    }

    public void incrementSectionRewrite(String sectionName) {
        Objects.requireNonNull(sectionName, "Section name cannot be null");
        sectionRewriteCounts.merge(sectionName, 1, Integer::sum);
    }

    public int getSectionRewriteCount(String sectionName) {
        return sectionRewriteCounts.getOrDefault(sectionName, 0);
    }

    public void setAcceptanceCriteriaCount(int count) {
        this.acceptanceCriteriaCount = count;
    }

    public int getAcceptanceCriteriaCount() {
        return acceptanceCriteriaCount;
    }

    public void setOpenQuestionsCount(int count) {
        this.openQuestionsCount = count;
    }

    public int getOpenQuestionsCount() {
        return openQuestionsCount;
    }

    public void setRequiresLegalInference(boolean requires) {
        this.requiresLegalInference = requires;
    }

    public boolean requiresLegalInference() {
        return requiresLegalInference;
    }

    public void setRequiresFinancialInference(boolean requires) {
        this.requiresFinancialInference = requires;
    }

    public boolean requiresFinancialInference() {
        return requiresFinancialInference;
    }

    public void setRequiresSecurityInference(boolean requires) {
        this.requiresSecurityInference = requires;
    }

    public boolean requiresSecurityInference() {
        return requiresSecurityInference;
    }

    public void setRequiresRegulatoryInference(boolean requires) {
        this.requiresRegulatoryInference = requires;
    }

    public boolean requiresRegulatoryInference() {
        return requiresRegulatoryInference;
    }

    public boolean requiresAnyUnsafeInference() {
        return requiresLegalInference || requiresFinancialInference || 
               requiresSecurityInference || requiresRegulatoryInference;
    }

    @Override
    public String toString() {
        return String.format("ProcessingContext{scenarios=%d, questions=%d, unsafeInference=%s}", 
                           acceptanceCriteriaCount, openQuestionsCount, requiresAnyUnsafeInference());
    }
}
