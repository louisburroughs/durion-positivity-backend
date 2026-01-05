package com.pos.agent.story.loop;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Enhanced unsafe inference detection using intelligent pattern matching.
 * Avoids false positives from benign mentions of sensitive keywords.
 * Only flags when the story asks AI to DESIGN/IMPLEMENT sensitive systems.
 * 
 * Safe examples (not flagged):
 * - "Ensure proper security and validation"
 * - "Audit includes who/why"
 * - "Implement proper error handling"
 * 
 * Dangerous examples (flagged):
 * - "Design authentication system"
 * - "Build compliance framework"
 * - "Implement payment processing"
 */
public class IntelligentUnsafeInferenceDetector {

    // Dangerous action verbs that indicate designing/implementing systems
    private static final String[] DANGEROUS_VERBS = {
            "implement", "design", "build", "create", "develop",
            "establish", "configure", "set up", "construct",
            "devise", "formulate", "architect", "engineer"
    };

    // Safe action verbs that don't trigger dangerous inference patterns
    // These are routine operational requirements
    private static final String[] SAFE_VERBS = {
            "ensure", "maintain", "verify", "check", "validate",
            "include", "track", "monitor", "log", "add"
    };

    private static final Set<String> SECURITY_KEYWORDS = Set.of(
            "security", "authentication", "authorization", "encryption", "credential",
            "password", "token", "certificate", "vulnerability", "vulnerabilities",
            "threat", "attack");

    private static final Set<String> FINANCIAL_KEYWORDS = Set.of(
            "financial", "payment", "transaction", "money", "currency", "tax",
            "accounting", "invoice", "billing", "pricing", "revenue", "cost");

    private static final Set<String> LEGAL_KEYWORDS = Set.of(
            "legal", "law", "statute", "contract", "liability", "jurisdiction",
            "litigation", "lawsuit", "attorney", "counsel");

    private static final Set<String> REGULATORY_KEYWORDS = Set.of(
            "regulatory", "compliance", "audit", "policy", "governance", "standard",
            "certification", "accreditation", "mandate", "requirement", "regulation",
            "gdpr", "hipaa", "ccpa", "sox", "pci");

    /**
     * Detects dangerous patterns in the issue text using intelligent matching.
     * 
     * @param issueText The issue text to analyze
     * @return A map of category -> set of dangerous patterns found
     */
    public Map<String, Set<String>> detectDangerousPatterns(String issueText) {
        Map<String, Set<String>> results = new LinkedHashMap<>();

        results.put("security", findDangerousPatterns(issueText, SECURITY_KEYWORDS));
        results.put("financial", findDangerousPatterns(issueText, FINANCIAL_KEYWORDS));
        results.put("legal", findDangerousPatterns(issueText, LEGAL_KEYWORDS));
        results.put("regulatory", findDangerousPatterns(issueText, REGULATORY_KEYWORDS));

        return results;
    }

    /**
     * Finds dangerous action patterns where the story asks AI to design/implement
     * sensitive systems.
     * 
     * @param text     The text to search
     * @param keywords Keywords for this category
     * @return Set of dangerous patterns found (e.g., "design authentication",
     *         "implement payment")
     */
    private Set<String> findDangerousPatterns(String text, Set<String> keywords) {
        Set<String> dangerousPatterns = new LinkedHashSet<>();

        for (String keyword : keywords) {
            for (String verb : DANGEROUS_VERBS) {
                // Look for pattern: verb ... keyword (within reasonable distance)
                // This captures "design authentication", "implement compliance", etc.
                if (containsDangerousPattern(text, verb, keyword)) {
                    String pattern = verb + " " + keyword;
                    // Only add if it's actually dangerous (not a false positive)
                    if (!isFalsePositive(pattern, text)) {
                        dangerousPatterns.add(pattern);
                    }
                }
            }
        }

        return dangerousPatterns;
    }

    /**
     * Checks if text contains a dangerous verb-keyword pattern.
     * Uses word boundary matching to avoid false positives.
     * 
     * @param text    The text to search
     * @param verb    The action verb
     * @param keyword The sensitive keyword
     * @return true if dangerous pattern found
     */
    private boolean containsDangerousPattern(String text, String verb, String keyword) {
        String lowerText = text.toLowerCase();

        // Pattern: verb ... keyword within same sentence
        // Allows for a few words between verb and keyword
        Pattern pattern = Pattern.compile(
                "\\b" + Pattern.quote(verb) + "\\b[^.!?\\n]{0,200}?\\b" + Pattern.quote(keyword) + "\\b");

        return pattern.matcher(lowerText).find();
    }

    /**
     * Checks if a dangerous pattern is actually safe (false positive).
     * 
     * @param pattern The pattern to check
     * @param text    The surrounding text
     * @return true if this is a false positive that should be ignored
     */
    public boolean isFalsePositive(String pattern, String text) {
        String lowerText = text.toLowerCase();

        // Extract the verb and keyword
        String[] parts = pattern.split(" ");
        if (parts.length < 2)
            return false;

        String keyword = parts[parts.length - 1];

        // Narrow false-positive handling: ignore benign best-practice phrasing.
        if ("standard".equals(keyword)) {
            Pattern standardApi = Pattern.compile("standard\\s+(rest|api|endpoint|service|payload)");
            if (standardApi.matcher(lowerText).find()) {
                return true;
            }
            Pattern standardSecurity = Pattern.compile("standard\\s+security\\s+(practices|controls|validation)");
            if (standardSecurity.matcher(lowerText).find()) {
                return true;
            }
        }

        if ("security".equals(keyword)) {
            Pattern securityPractices = Pattern.compile("security\\s+(practices|controls|validation)");
            if (securityPractices.matcher(lowerText).find()) {
                return true;
            }
            Pattern properSecurity = Pattern.compile("proper\\s+security");
            if (properSecurity.matcher(lowerText).find()) {
                return true;
            }
            Pattern securityAndValidation = Pattern.compile("security\\s+and\\s+validation");
            if (securityAndValidation.matcher(lowerText).find()) {
                return true;
            }
        }

        return false;
    }

    /**
     * Generates a human-readable diagnostic message.
     * 
     * @param patterns Map of category -> patterns found
     * @return Formatted diagnostic message
     */
    public String generateDiagnostic(Map<String, Set<String>> patterns) {
        StringBuilder report = new StringBuilder();

        boolean hasPatterns = patterns.values().stream().anyMatch(set -> !set.isEmpty());
        if (!hasPatterns) {
            return "";
        }

        report.append("🛑 STOP: Unsafe inference required\n\n");

        if (!patterns.get("security").isEmpty()) {
            report.append("🔒 Security design request detected:\n");
            for (String pattern : patterns.get("security")) {
                report.append("   • ").append(pattern).append("\n");
            }
            report.append("\n");
        }

        if (!patterns.get("financial").isEmpty()) {
            report.append("💰 Financial system design request detected:\n");
            for (String pattern : patterns.get("financial")) {
                report.append("   • ").append(pattern).append("\n");
            }
            report.append("\n");
        }

        if (!patterns.get("legal").isEmpty()) {
            report.append("📋 Legal system design request detected:\n");
            for (String pattern : patterns.get("legal")) {
                report.append("   • ").append(pattern).append("\n");
            }
            report.append("\n");
        }

        if (!patterns.get("regulatory").isEmpty()) {
            report.append("⚖️ Regulatory compliance design request detected:\n");
            for (String pattern : patterns.get("regulatory")) {
                report.append("   • ").append(pattern).append("\n");
            }
            report.append("\n");
        }

        report.append(
                "Action: Please consult subject matter experts instead of asking AI to design these systems.\n\n");
        report.append("Reason: AI agents cannot safely infer requirements for sensitive domains. ")
                .append("These topics require human expertise, decision-making, and domain knowledge.");

        return report.toString();
    }
}
