package com.pos.agent.story;

import com.pos.agent.impl.StoryStrengtheningAgent;
import com.pos.agent.story.analysis.RequirementsAnalyzer;
import com.pos.agent.story.config.GitHubApiClient;
import com.pos.agent.story.config.GitHubApiException;
import com.pos.agent.story.config.StoryConfiguration;
import com.pos.agent.story.config.StoryLogger;
import com.pos.agent.story.loop.LoopDetector;
import com.pos.agent.story.models.GitHubIssue;
import com.pos.agent.story.output.OutputGenerator;
import com.pos.agent.story.parsing.IssueParser;
import com.pos.agent.story.transformation.RequirementsTransformer;
import com.pos.agent.story.validation.IssueValidator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

/**
 * Main execution framework for the Story Strengthening Agent.
 * Coordinates GitHub API integration, user interaction, and story processing.
 * 
 * Follows the reference pattern from
 * workspace-agents/src/main/java/com/durion/audit/MissingIssuesAuditSystem.java
 * 
 * Requirements: All requirements - Main application workflow
 */
public class StoryStrengtheningSystem {

    private final GitHubApiClient githubClient;
    private final StoryStrengtheningAgent agent;
    private final StoryConfiguration configuration;
    private final StoryLogger logger;
    private final String outputDirectory;
    private final boolean batchMode;

    private static final DateTimeFormatter FILE_TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    /**
     * Creates a new Story Strengthening System with the specified configuration.
     * 
     * @param githubToken     GitHub personal access token
     * @param outputDirectory Directory for output files
     * @param bypassSSL       Whether to bypass SSL certificate validation (for
     *                        development only)
     */
    public StoryStrengtheningSystem(String githubToken, String outputDirectory, boolean bypassSSL) {
        this(githubToken, outputDirectory, bypassSSL, false);
    }

    public StoryStrengtheningSystem(String githubToken, String outputDirectory, boolean bypassSSL, boolean batchMode) {
        this.outputDirectory = outputDirectory;
        this.batchMode = batchMode;
        this.logger = new StoryLogger("story-strengthening-" + System.currentTimeMillis());

        // Initialize GitHub API client
        this.githubClient = new GitHubApiClient(githubToken, bypassSSL);

        // Initialize configuration with defaults
        this.configuration = StoryConfiguration.builder()
                .allowedRepository("durion-positivity-backend")
                .requiredIssuePrefix("[BACKEND] [STORY]")
                .maxRewriteIterations(2)
                .maxAcceptanceCriteria(25)
                .maxOpenQuestions(10)
                .enableLoopDetection(true)
                .build();

        // Initialize agent components
        IssueValidator validator = new com.pos.agent.story.validation.DefaultIssueValidator();
        IssueParser parser = new IssueParser();
        RequirementsAnalyzer analyzer = new RequirementsAnalyzer();
        RequirementsTransformer transformer = new com.pos.agent.story.transformation.DefaultRequirementsTransformer();
        OutputGenerator outputGenerator = new OutputGenerator();
        LoopDetector loopDetector = new LoopDetector();

        // Create the agent
        this.agent = new StoryStrengtheningAgent(
                validator, parser, analyzer, transformer,
                outputGenerator, loopDetector, configuration);
    }

    /**
     * Processes a single GitHub issue through the story strengthening pipeline.
     * 
     * @param repository  Repository in format "owner/repo"
     * @param issueNumber Issue number to process
     * @return Path to the output file
     * @throws IOException          if processing fails
     * @throws InterruptedException if processing is interrupted
     */
    public Path processIssue(String repository, int issueNumber)
            throws IOException, InterruptedException {
        System.out.println("🚀 Starting Story Strengthening Process");
        System.out.println("========================================");
        System.out.println("📋 Repository: " + repository);
        System.out.println("🔢 Issue Number: " + issueNumber);
        System.out.println();

        try {
            // Step 1: Test GitHub connection
            System.out.println("🔗 Step 1: Testing GitHub connection...");
            if (!githubClient.testConnection()) {
                throw new IOException("GitHub connection test failed. Check token permissions.");
            }
            System.out.println("   ✅ GitHub connection successful");
            System.out.println();

            // Step 2: Fetch issue from GitHub
            System.out.println("📥 Step 2: Fetching issue from GitHub...");
            GitHubIssue issue = githubClient.getIssue(repository, issueNumber);
            System.out.println("   ✅ Issue fetched successfully");
            System.out.println("   📝 Title: " + issue.getTitle());
            System.out.println("   🏷️ Labels: " + String.join(", ", issue.getLabels()));
            System.out.println();

            // Step 3: Display issue preview and get user confirmation
            System.out.println("📄 Step 3: Issue Preview");
            System.out.println("========================");
            displayIssuePreview(issue);
            System.out.println();

            if (!getUserConfirmation("Do you want to proceed with strengthening this story? (y/N): ")) {
                System.out.println("❌ Processing cancelled by user");
                return null;
            }
            System.out.println();

            // Step 4: Process issue through agent pipeline
            System.out.println("🔄 Step 4: Processing issue through strengthening pipeline...");
            logger.logValidationStart(issue);

            long startTime = System.currentTimeMillis();
            StoryStrengtheningAgent.ProcessingResult result = agent.processIssue(issue);
            long durationMs = System.currentTimeMillis() - startTime;

            System.out.println("   ⏱️ Processing completed in " + durationMs + "ms");
            System.out.println();

            // Step 5: Handle result
            if (result.isSuccess()) {
                System.out.println("✅ Step 5: Story strengthening successful!");
                System.out.println();

                // Save output to file
                Path outputPath = saveOutput(repository, issueNumber, result.getOutput());
                System.out.println("💾 Output saved to: " + outputPath);
                System.out.println();

                // Display summary
                displaySuccessSummary(result.getOutput());

                logger.logSessionEnd(true, "SUCCESS");
                return outputPath;

            } else {
                System.out.println("❌ Step 5: Story strengthening stopped");
                System.out.println();
                System.out.println(result.getStopPhrase());
                System.out.println();

                logger.logSessionEnd(false, "STOPPED: " + result.getStopPhrase());
                return null;
            }

        } catch (GitHubApiException e) {
            System.err.println("❌ GitHub API Error: " + e.getMessage());
            logger.logError("GitHub API", e, "Issue: " + repository + "#" + issueNumber);
            throw new IOException("GitHub API error", e);
        } catch (Exception e) {
            System.err.println("❌ Unexpected Error: " + e.getMessage());
            logger.logError("Processing", e, "Issue: " + repository + "#" + issueNumber);
            throw e;
        }
    }

    /**
     * Displays a preview of the issue to the user.
     */
    private void displayIssuePreview(GitHubIssue issue) {
        System.out.println("Title: " + issue.getTitle());
        System.out.println("Repository: " + issue.getRepository());
        System.out.println("Labels: " + String.join(", ", issue.getLabels()));
        System.out.println();
        System.out.println("Body Preview (first 500 characters):");
        System.out.println("─────────────────────────────────────");
        String body = issue.getBody();
        if (body.length() > 500) {
            System.out.println(body.substring(0, 500) + "...");
        } else {
            System.out.println(body);
        }
        System.out.println("─────────────────────────────────────");
    }

    /**
     * Gets user confirmation for processing.
     */
    private boolean getUserConfirmation(String prompt) {
        if (batchMode) {
            return true;
        }
        System.out.print(prompt);
        Scanner scanner = new Scanner(System.in);
        String response = scanner.nextLine().trim().toLowerCase();
        return response.equals("y") || response.equals("yes");
    }

    /**
     * Saves the strengthened output to a file.
     */
    private Path saveOutput(String repository, int issueNumber, String output) throws IOException {
        // Create output directory if it doesn't exist
        Path outputDir = Paths.get(outputDirectory);
        if (!Files.exists(outputDir)) {
            Files.createDirectories(outputDir);
        }

        // Generate filename with timestamp
        String timestamp = LocalDateTime.now().format(FILE_TIMESTAMP_FORMAT);
        String repoName = repository.replace("/", "_");
        String filename = String.format("story_%s_issue_%d_%s.md", repoName, issueNumber, timestamp);
        Path outputPath = outputDir.resolve(filename);

        // Write output to file
        Files.writeString(outputPath, output, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

        return outputPath;
    }

    /**
     * Displays a success summary with key metrics.
     */
    private void displaySuccessSummary(String output) {
        System.out.println("📊 Processing Summary");
        System.out.println("====================");

        // Count sections
        int sectionCount = countOccurrences(output, "## ");
        System.out.println("📑 Sections Generated: " + sectionCount);

        // Count acceptance criteria
        int acceptanceCriteriaCount = countOccurrences(output, "### Scenario:");
        System.out.println("✅ Acceptance Criteria: " + acceptanceCriteriaCount);

        // Count open questions
        int openQuestionsCount = countOccurrences(output, "**Q:");
        System.out.println("❓ Open Questions: " + openQuestionsCount);

        // Output size
        System.out.println("📏 Output Size: " + output.length() + " characters");

        System.out.println();
    }

    /**
     * Counts occurrences of a substring in a string.
     */
    private int countOccurrences(String text, String substring) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(substring, index)) != -1) {
            count++;
            index += substring.length();
        }
        return count;
    }

    private static List<Integer> parseIssueNumbers(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            throw new IllegalArgumentException("Issue numbers are required");
        }
        List<Integer> issueNumbers = new ArrayList<>();
        for (String part : raw.split(",")) {
            String token = part.trim();
            if (token.isEmpty()) {
                continue;
            }
            if (token.contains("-")) {
                String[] rangeParts = token.split("-", 2);
                if (rangeParts.length != 2) {
                    throw new IllegalArgumentException("Invalid range segment: " + token);
                }
                int start = Integer.parseInt(rangeParts[0].trim());
                int end = Integer.parseInt(rangeParts[1].trim());
                if (start > end) {
                    throw new IllegalArgumentException("Range start greater than end: " + token);
                }
                for (int i = start; i <= end; i++) {
                    issueNumbers.add(i);
                }
            } else {
                issueNumbers.add(Integer.parseInt(token));
            }
        }
        return issueNumbers;
    }

    /**
     * Main entry point for command-line execution.
     * 
     * Usage: java StoryStrengtheningSystem <repository> <issue-number(s)> [options]
     * 
     * Options:
     * --output-dir <path> Output directory (default: ./story-output)
     * --bypass-ssl Bypass SSL certificate validation (development only)
     * --batch Skip interactive confirmations
     * 
     * Environment Variables:
     * GITHUB_TOKEN GitHub personal access token (required)
     */
    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("Usage: java StoryStrengtheningSystem <repository> <issue-number(s)> [options]");
            System.err.println();
            System.err.println("Arguments:");
            System.err.println("  <repository>         Repository in format 'owner/repo'");
            System.err.println("  <issue-number(s)>    Single (123), comma list (123,124), or range (123-127)");
            System.err.println();
            System.err.println("Options:");
            System.err.println("  --output-dir <path>    Output directory (default: ./story-output)");
            System.err.println("  --bypass-ssl           Bypass SSL certificate validation (development only)");
            System.err.println("  --batch                Skip interactive confirmations");
            System.err.println();
            System.err.println("Environment Variables:");
            System.err.println("  GITHUB_TOKEN           GitHub personal access token (required)");
            System.err.println();
            System.err.println("Example:");
            System.err.println(
                    "  java StoryStrengtheningSystem louisburroughs/durion-positivity-backend 123-125 --batch");
            System.exit(1);
        }

        try {
            // Parse arguments
            String repository = args[0];
            String issueNumbersArg = args[1];
            String outputDir = "./story-output";
            boolean bypassSSL = false;
            boolean batchMode = false;

            // Parse options
            for (int i = 2; i < args.length; i++) {
                if (args[i].equals("--output-dir") && i + 1 < args.length) {
                    outputDir = args[++i];
                } else if (args[i].equals("--bypass-ssl")) {
                    bypassSSL = true;
                } else if (args[i].equals("--batch")) {
                    batchMode = true;
                }
            }

            // Expand issue numbers (single, comma list, range)
            List<Integer> issueNumbers = parseIssueNumbers(issueNumbersArg);
            if (issueNumbers.isEmpty()) {
                throw new IllegalArgumentException("No issue numbers provided");
            }

            // Get GitHub token from environment
            String githubToken = System.getenv("GITHUB_TOKEN");
            if (githubToken == null || githubToken.trim().isEmpty()) {
                System.err.println("❌ Error: GITHUB_TOKEN environment variable not set");
                System.err.println("   Please set your GitHub personal access token:");
                System.err.println("   export GITHUB_TOKEN=your_token_here");
                System.exit(1);
            }

            // Create and run system
            StoryStrengtheningSystem system = new StoryStrengtheningSystem(githubToken, outputDir, bypassSSL,
                    batchMode);
            boolean allSuccess = true;
            boolean anyProcessed = false;

            for (int issueNumber : issueNumbers) {
                Path outputPath = system.processIssue(repository, issueNumber);
                if (outputPath != null) {
                    anyProcessed = true;
                } else {
                    allSuccess = false;
                }
            }

            if (allSuccess && anyProcessed) {
                System.out.println("✅ Story strengthening completed successfully for all issues!");
                System.exit(0);
            } else if (anyProcessed) {
                System.out.println("⚠️ Story strengthening completed with some failures or cancellations");
                System.exit(2);
            } else {
                System.out.println("❌ Story strengthening was cancelled or failed for all issues");
                System.exit(1);
            }

        } catch (NumberFormatException e) {
            System.err.println("❌ Error: Issue number must be a valid integer or range/list");
            System.exit(1);
        } catch (IllegalArgumentException e) {
            System.err.println("❌ Error: " + e.getMessage());
            System.exit(1);
        } catch (IOException e) {
            System.err.println("❌ Error: " + e.getMessage());
            System.exit(1);
        } catch (InterruptedException e) {
            System.err.println("❌ Error: Processing was interrupted");
            Thread.currentThread().interrupt();
            System.exit(1);
        } catch (Exception e) {
            System.err.println("❌ Unexpected error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    // Getters for testing
    public GitHubApiClient getGithubClient() {
        return githubClient;
    }

    public StoryStrengtheningAgent getAgent() {
        return agent;
    }

    public StoryConfiguration getConfiguration() {
        return configuration;
    }

    public StoryLogger getLogger() {
        return logger;
    }
}
