package com.pos.agent.story.config;

import com.pos.agent.story.models.GitHubIssue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * GitHub API Client for Story Strengthening Agent.
 * Handles issue reading, authentication, rate limiting, and error handling.
 * 
 * Follows reference pattern from
 * workspace-agents/src/main/java/com/durion/GitHubApiClientSSLBypass.java
 * 
 * Requirements: 2.1, 2.2, 2.3, 2.4
 */
public class GitHubApiClient {

    private final HttpClient httpClient;
    private final String githubToken;
    private static final String GITHUB_API_BASE = "https://api.github.com";
    private static final int MAX_RETRIES = 3;
    private static final long RETRY_DELAY_MS = 2000;

    /**
     * Creates a new GitHub API client with the specified token.
     * 
     * @param githubToken GitHub personal access token
     * @throws IllegalArgumentException if token is null or empty
     */
    public GitHubApiClient(String githubToken) {
        this(githubToken, false);
    }

    /**
     * Creates a new GitHub API client with the specified token and SSL bypass
     * option.
     * 
     * @param githubToken GitHub personal access token
     * @param bypassSSL   Whether to bypass SSL certificate validation (for
     *                    development only)
     * @throws IllegalArgumentException if token is null or empty
     */
    public GitHubApiClient(String githubToken, boolean bypassSSL) {
        if (githubToken == null || githubToken.trim().isEmpty()) {
            throw new IllegalArgumentException("GitHub token cannot be null or empty");
        }
        this.githubToken = githubToken;
        this.httpClient = bypassSSL ? createSSLBypassHttpClient() : createHttpClient();
    }

    /**
     * Creates a standard HttpClient with SSL validation.
     */
    private HttpClient createHttpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    /**
     * Creates an HttpClient that bypasses SSL certificate validation.
     * WARNING: Only use in development environments!
     */
    private HttpClient createSSLBypassHttpClient() {
        try {
            TrustManager[] trustAllCerts = new TrustManager[] {
                    new X509TrustManager() {
                        public X509Certificate[] getAcceptedIssuers() {
                            return null;
                        }

                        public void checkClientTrusted(X509Certificate[] certs, String authType) {
                        }

                        public void checkServerTrusted(X509Certificate[] certs, String authType) {
                        }
                    }
            };

            SSLContext sslContext = SSLContext.getInstance("SSL");
            sslContext.init(null, trustAllCerts, new java.security.SecureRandom());

            return HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(30))
                    .sslContext(sslContext)
                    .build();

        } catch (NoSuchAlgorithmException | KeyManagementException e) {
            // Fall back to standard client if SSL bypass fails
            return createHttpClient();
        }
    }

    /**
     * Fetches a single issue from a repository by issue number.
     * 
     * @param repository  Repository in format "owner/repo"
     * @param issueNumber Issue number
     * @return GitHubIssue object
     * @throws GitHubApiException   if issue not found, API error occurs, or SSL
     *                              error occurs
     * @throws InterruptedException if request is interrupted
     */
    public GitHubIssue getIssue(String repository, int issueNumber)
            throws GitHubApiException, InterruptedException {
        Objects.requireNonNull(repository, "Repository cannot be null");
        if (issueNumber <= 0) {
            throw new IllegalArgumentException("Issue number must be positive");
        }

        String url = String.format("%s/repos/%s/issues/%d", GITHUB_API_BASE, repository, issueNumber);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + githubToken)
                .header("Accept", "application/vnd.github.v3+json")
                .header("User-Agent", "POS-Agent-Framework/1.0")
                .GET()
                .build();

        HttpResponse<String> response = sendRequestWithRetry(request);

        if (response.statusCode() == 200) {
            return parseIssueFromJson(response.body());
        } else if (response.statusCode() == 404) {
            throw new GitHubApiException("Issue not found: " + repository + "#" + issueNumber);
        } else if (response.statusCode() == 401) {
            throw new GitHubApiException("Authentication failed - invalid token");
        } else if (response.statusCode() == 403) {
            throw new GitHubApiException("Rate limit exceeded or access forbidden");
        } else {
            throw new GitHubApiException("GitHub API error: " + response.statusCode() + " - " + response.body());
        }
    }

    /**
     * Tests the connection to GitHub API with current token.
     * 
     * @return true if connection successful, false otherwise
     */
    public boolean testConnection() {
        try {
            String url = GITHUB_API_BASE + "/user";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + githubToken)
                    .header("Accept", "application/vnd.github.v3+json")
                    .header("User-Agent", "POS-Agent-Framework/1.0")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            return response.statusCode() == 200;

        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Checks GitHub API rate limit status and waits if necessary.
     * 
     * @throws IOException          if API request fails
     * @throws InterruptedException if wait is interrupted
     */
    public void checkRateLimitAndWait() throws IOException, InterruptedException {
        String url = GITHUB_API_BASE + "/rate_limit";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + githubToken)
                .header("Accept", "application/vnd.github.v3+json")
                .header("User-Agent", "POS-Agent-Framework/1.0")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 200) {
            String responseBody = response.body();

            // Parse rate limit info
            Pattern coreRemainingPattern = Pattern.compile("\"core\"\\s*:\\s*\\{[^}]*\"remaining\"\\s*:\\s*(\\d+)");
            Pattern coreResetPattern = Pattern.compile("\"core\"\\s*:\\s*\\{[^}]*\"reset\"\\s*:\\s*(\\d+)");

            Matcher remainingMatcher = coreRemainingPattern.matcher(responseBody);
            Matcher resetMatcher = coreResetPattern.matcher(responseBody);

            if (remainingMatcher.find() && resetMatcher.find()) {
                int remaining = Integer.parseInt(remainingMatcher.group(1));
                long resetTime = Long.parseLong(resetMatcher.group(1));
                long currentTime = System.currentTimeMillis() / 1000;
                long waitTime = resetTime - currentTime;

                if (remaining < 10 && waitTime > 0) {
                    // Wait for rate limit reset with buffer
                    Thread.sleep(waitTime * 1000 + 5000);
                } else if (remaining < 100) {
                    // Add small delay when getting low
                    Thread.sleep(2000);
                }
            }
        }
    }

    /**
     * Sends an HTTP request with retry logic and exponential backoff.
     * 
     * @param request HTTP request to send
     * @return HTTP response
     * @throws GitHubApiException   if all retries fail or SSL error occurs
     * @throws InterruptedException if request is interrupted
     */
    private HttpResponse<String> sendRequestWithRetry(HttpRequest request)
            throws GitHubApiException, InterruptedException {
        Exception lastException = null;

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                // Check rate limit before request (skip on first attempt to avoid
                // double-checking)
                if (attempt > 1) {
                    try {
                        checkRateLimitAndWait();
                    } catch (IOException e) {
                        // If rate limit check fails, continue with request anyway
                    }
                }

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                // If rate limited, wait and retry
                if (response.statusCode() == 403 && response.body().contains("rate limit")) {
                    if (attempt < MAX_RETRIES) {
                        long delay = RETRY_DELAY_MS * (long) Math.pow(2, attempt - 1);
                        Thread.sleep(delay);
                        continue;
                    }
                }

                return response;

            } catch (javax.net.ssl.SSLHandshakeException e) {
                // SSL errors are not retryable - fail immediately
                throw new GitHubApiException("SSL certificate validation failed: " + e.getMessage(), e);
            } catch (IOException e) {
                lastException = e;
                if (attempt < MAX_RETRIES) {
                    long delay = RETRY_DELAY_MS * (long) Math.pow(2, attempt - 1);
                    Thread.sleep(delay);
                }
            }
        }

        throw new GitHubApiException(
                "Request failed after " + MAX_RETRIES + " attempts: " +
                        (lastException != null ? lastException.getMessage() : "Unknown error"),
                lastException);
    }

    /**
     * Parses a GitHubIssue from JSON response.
     * 
     * @param json JSON response body
     * @return GitHubIssue object
     * @throws GitHubApiException if JSON parsing fails
     */
    private GitHubIssue parseIssueFromJson(String json) throws GitHubApiException {
        try {
            Pattern numberPattern = Pattern.compile("\"number\"\\s*:\\s*(\\d+)");
            Pattern titlePattern = Pattern.compile("\"title\"\\s*:\\s*\"([^\"]*?)\"");
            Pattern bodyPattern = Pattern.compile("\"body\"\\s*:\\s*\"([^\"]*?)\"");
            Pattern repoPattern = Pattern.compile("\"repository_url\"\\s*:\\s*\"[^\"]*?/repos/([^\"]+)\"");

            Matcher numberMatcher = numberPattern.matcher(json);
            Matcher titleMatcher = titlePattern.matcher(json);
            Matcher bodyMatcher = bodyPattern.matcher(json);
            Matcher repoMatcher = repoPattern.matcher(json);

            if (numberMatcher.find() && titleMatcher.find()) {
                int number = Integer.parseInt(numberMatcher.group(1));
                String title = unescapeJson(titleMatcher.group(1));
                String body = bodyMatcher.find() ? unescapeJson(bodyMatcher.group(1)) : "";
                String repository = repoMatcher.find() ? repoMatcher.group(1) : "unknown";

                // Extract labels
                List<String> labels = extractLabelsFromJson(json);

                return new GitHubIssue(title, body, labels, repository, number);
            }

            throw new GitHubApiException("Failed to parse issue from JSON");

        } catch (Exception e) {
            throw new GitHubApiException("JSON parsing error: " + e.getMessage(), e);
        }
    }

    /**
     * Extracts labels from JSON response.
     * 
     * @param json JSON response body
     * @return List of label names
     */
    private List<String> extractLabelsFromJson(String json) {
        List<String> labels = new ArrayList<>();

        Pattern labelsPattern = Pattern.compile("\"labels\"\\s*:\\s*\\[(.*?)\\]");
        Matcher labelsMatcher = labelsPattern.matcher(json);

        if (labelsMatcher.find()) {
            String labelsArray = labelsMatcher.group(1);

            Pattern labelNamePattern = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]*?)\"");
            Matcher labelNameMatcher = labelNamePattern.matcher(labelsArray);

            while (labelNameMatcher.find()) {
                String labelName = unescapeJson(labelNameMatcher.group(1));
                labels.add(labelName);
            }
        }

        return labels;
    }

    /**
     * Unescapes JSON string values.
     * 
     * @param str Escaped JSON string
     * @return Unescaped string
     */
    private String unescapeJson(String str) {
        if (str == null)
            return "";
        return str.replace("\\\"", "\"")
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t")
                .replace("\\\\", "\\");
    }

}
