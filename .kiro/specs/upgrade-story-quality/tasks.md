# Implementation Plan

- [x] 1. Set up project structure and core interfaces




  - Create package structure under `com.positivity.agent.story` in pos-agent-framework
  - Create subpackages: validation, parsing, analysis, transformation, output, loop, models, config
  - Define core interfaces following reference implementation patterns from workspace-agents
  - Verify jqwik is configured in pom.xml (already present)
  - Add markdown parsing library dependency to pom.xml (commonmark-java or flexmark-java)
  - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5_

- [x] 2. Implement data models and validation




  - [x] 2.1 Create core data model classes in models package


    - Implement GitHubIssue, ValidationResult, ParsedIssue, AnalysisResult, Requirement, OpenQuestion, TransformedRequirements, GherkinScenario as Java records or POJOs
    - Follow reference patterns from workspace-agents/audit (GitHubIssue, StoryMetadata)
    - Use proper encapsulation and immutability where appropriate
    - _Requirements: 2.1, 2.2, 2.3, 2.4_

  - [x] 2.2 Write property test for metadata extraction


    - **Property 5: Metadata extraction completeness**
    - **Validates: Requirements 2.1, 2.2, 2.3, 2.4**

  - [x] 2.3 Implement IssueValidator component in validation package


    - Create repository name validation logic (durion-positivity-backend only)
    - Create issue prefix validation logic ([BACKEND] [STORY])
    - Create story type detection logic (functional story vs epic/task/bug)
    - Implement stop phrase emission for validation failures
    - Follow reference patterns from workspace-agents/audit/AuditEngine
    - _Requirements: 1.1, 1.2, 1.3, 1.4_

  - [x] 2.4 Write property test for repository validation


    - **Property 1: Repository validation**
    - **Validates: Requirements 1.1**

  - [x] 2.5 Write property test for issue prefix validation


    - **Property 2: Issue prefix validation**
    - **Validates: Requirements 1.2**

  - [x] 2.6 Write property test for stop phrase emission


    - **Property 3: Stop phrase emission on validation failure**
    - **Validates: Requirements 1.4**

  - [x] 2.7 Write property test for processing continuation


    - **Property 4: Processing continuation on valid issues**
    - **Validates: Requirements 1.5**

- [-] 3. Implement issue parsing



  - [x] 3.1 Create IssueParser component in parsing package


    - Implement metadata extraction (title, body, labels, repository)
    - Implement markdown parsing logic using a markdown library (consider commonmark-java or flexmark)
    - Create section identification logic for parsing issue body structure
    - Follow reference patterns from workspace-agents/audit/StoryMetadataParser
    - _Requirements: 2.1, 2.2, 2.3, 2.4_

  - [ ] 3.2 Write unit tests for IssueParser


    - Test metadata extraction with various issue formats
    - Test markdown parsing with different structures
    - Verify all fields are captured correctly
    - _Requirements: 2.1, 2.2, 2.3, 2.4_

- [ ] 4. Implement requirements analysis
  - [ ] 4.1 Create RequirementsAnalyzer component in analysis package
    - Implement actor and stakeholder identification using pattern matching
    - Implement business intent extraction from issue body
    - Implement precondition and state detection
    - Implement functional requirement identification
    - Implement error flow detection
    - Implement business rule extraction
    - Implement data requirement identification
    - Implement ambiguity flagging logic (vague terms, missing information)
    - _Requirements: 3.1, 7.1, 7.3, 8.1, 8.2, 8.3, 8.4, 8.5_

  - [ ] 4.2 Write unit tests for RequirementsAnalyzer
    - Test actor identification with various issue formats
    - Test intent extraction with different writing styles
    - Test ambiguity detection with known unclear scenarios
    - _Requirements: 3.1, 7.1, 7.3, 8.1, 8.2, 8.3, 8.4, 8.5_

- [ ] 5. Implement EARS pattern transformation
  - [ ] 5.1 Create EARS pattern transformer in transformation package
    - Implement ubiquitous pattern application (THE system SHALL)
    - Implement state-driven pattern application (WHILE ... THE system SHALL)
    - Implement event-driven pattern application (WHEN ... THE system SHALL)
    - Implement unwanted behavior pattern application (IF ... THEN THE system SHALL)
    - Ensure all patterns use "the system shall" phrasing consistently
    - Create helper methods for pattern detection and application
    - _Requirements: 6.1, 6.2, 6.3, 6.4, 6.5_

  - [ ] 5.2 Write property test for EARS phrasing consistency
    - **Property 13: EARS phrasing consistency**
    - **Validates: Requirements 6.5**

  - [ ] 5.3 Write unit tests for EARS patterns
    - Test ubiquitous pattern application with sample requirements
    - Test state-driven pattern application with preconditions
    - Test event-driven pattern application with triggers
    - Test unwanted behavior pattern application with error cases
    - _Requirements: 6.1, 6.2, 6.3, 6.4_

- [ ] 6. Implement Gherkin transformation
  - [ ] 6.1 Create Gherkin scenario generator in transformation package
    - Implement Given/When/Then/And keyword usage with proper formatting
    - Implement verifiable Then clause generation (must be testable)
    - Implement compound condition avoidance (split into multiple clauses)
    - Implement prose-free Gherkin block generation (structured format only)
    - Implement modal verb filtering (should, may, might, could, ideally)
    - Create helper methods for Gherkin validation and formatting
    - _Requirements: 5.1, 5.2, 5.3, 5.4, 5.5, 5.6_

  - [ ] 6.2 Write property test for Gherkin keyword usage
    - **Property 10: Gherkin keyword usage**
    - **Validates: Requirements 5.1**

  - [ ] 6.3 Write property test for no prose in Gherkin
    - **Property 11: No prose in Gherkin blocks**
    - **Validates: Requirements 5.5**

  - [ ] 6.4 Write property test for no modal verbs
    - **Property 12: No modal verbs in Gherkin**
    - **Validates: Requirements 5.6**

  - [ ] 6.5 Write unit tests for Gherkin generation
    - Test Given/When/Then structure with various scenarios
    - Test compound condition detection and splitting
    - Test modal verb filtering with edge cases
    - _Requirements: 5.1, 5.4, 5.6_

- [ ] 7. Implement ISO/IEC/IEEE 29148 compliance checks
  - [ ] 7.1 Create quality standards validator in analysis package
    - Implement vague term detection and replacement (quickly, adequate, etc.)
    - Implement acceptance criteria presence verification
    - Implement requirement completeness checks (actor, trigger, outcome)
    - Implement terminology consistency checks across requirements
    - Implement verifiability flagging for untestable requirements
    - Create configurable lists of vague terms and quality rules
    - _Requirements: 7.1, 7.2, 7.3, 7.4, 7.6_

  - [ ] 7.2 Write property test for acceptance criteria presence
    - **Property 14: Acceptance criteria presence**
    - **Validates: Requirements 7.2**

  - [ ] 7.3 Write property test for requirement completeness
    - **Property 15: Requirement completeness**
    - **Validates: Requirements 7.3**

  - [ ] 7.4 Write unit tests for quality standards
    - Test vague term detection with known problematic terms
    - Test acceptance criteria verification with various formats
    - Test completeness checks with incomplete requirements
    - _Requirements: 7.1, 7.2, 7.3_

- [ ] 8. Implement open questions handling
  - [ ] 8.1 Create open question generator in analysis package
    - Implement question text generation for identified ambiguities
    - Implement "why it matters" impact description generation
    - Implement open question structure validation (question + impact)
    - Create categorization for different types of ambiguities
    - _Requirements: 8.1, 8.2, 8.3, 8.4, 8.5, 8.6, 8.7, 8.8_

  - [ ] 8.2 Write property test for open question structure
    - **Property 16: Open question structure**
    - **Validates: Requirements 8.6, 8.7**

  - [ ] 8.3 Write unit tests for open question generation
    - Test question text generation for various ambiguity types
    - Test impact description generation with different scenarios
    - Test structure validation with valid and invalid questions
    - _Requirements: 8.6, 8.7_

- [ ] 9. Implement output generation
  - [ ] 9.1 Create OutputGenerator component in output package
    - Implement mandatory section ordering (header, intent, actors, preconditions, functional requirements, alternate flows, business rules, data requirements, acceptance criteria, observability, open questions, original story)
    - Implement markdown formatting with proper headers and structure
    - Implement original story preservation (verbatim append at end)
    - Implement section title generation following standard format
    - Create markdown builder utilities for consistent formatting
    - _Requirements: 3.3, 3.4, 3.5, 4.1, 4.2, 4.3, 4.4, 4.5, 4.6, 4.7, 4.8, 4.9, 4.10, 4.11, 4.12, 9.1, 9.2, 9.3_

  - [ ] 9.2 Write property test for original content preservation
    - **Property 8: Original content preservation (round-trip)**
    - **Validates: Requirements 3.3, 3.4, 7.5, 9.1, 9.2, 9.3**

  - [ ] 9.3 Write property test for mandatory section ordering
    - **Property 9: Mandatory section ordering**
    - **Validates: Requirements 3.5, 4.1-4.12**

  - [ ] 9.4 Write property test for output generation
    - **Property 6: Output generation for valid inputs**
    - **Validates: Requirements 3.1**

  - [ ] 9.5 Write property test for open questions section
    - **Property 7: Open questions section inclusion**
    - **Validates: Requirements 3.2**

  - [ ] 9.6 Write unit tests for OutputGenerator
    - Test section ordering with various content combinations
    - Test original content preservation with special characters
    - Test markdown formatting correctness and escaping
    - _Requirements: 3.3, 3.5, 9.1_

- [ ] 10. Implement loop detection
  - [ ] 10.1 Create LoopDetector component in loop package
    - Implement rewrite iteration tracking (max 2 iterations per section)
    - Implement acceptance criteria counting (threshold: 25 scenarios)
    - Implement open questions counting (threshold: 10 questions)
    - Implement unsafe inference detection (legal, financial, security, regulatory keywords)
    - Implement stop phrase emission for loop conditions with specific messages
    - Follow reference patterns from workspace-agents for tracking and logging
    - _Requirements: 11.1, 11.2, 11.3, 11.4, 11.5, 11.6, 11.7_

  - [ ] 10.2 Write unit tests for LoopDetector
    - Test rewrite iteration counting with multiple passes
    - Test threshold detection (exactly 25 scenarios, exactly 10 questions, edge cases)
    - Test unsafe inference detection with various keyword combinations
    - _Requirements: 11.1, 11.2, 11.3, 11.4, 11.5, 11.6, 11.7_

- [ ] 11. Implement stop phrase handling
  - [ ] 11.1 Create stop phrase manager in validation package
    - Implement stop phrase emission for validation failures with specific messages
    - Implement stop phrase emission for processing errors with context
    - Implement stop phrase emission for loop detection with details
    - Ensure consistent stop phrase format (STOP: [reason])
    - Create enumeration or constants for all stop phrase types
    - _Requirements: 1.4, 10.1, 10.2, 10.3, 10.4, 10.5, 10.6, 10.7, 10.8, 11.1, 11.2, 11.3, 11.4, 11.5, 11.6, 11.7_

  - [ ] 11.2 Write unit tests for stop phrase handling
    - Test stop phrase emission for each condition type
    - Test stop phrase format consistency across all cases
    - Test stop phrase context information inclusion
    - _Requirements: 10.1, 10.2, 10.3, 10.4, 10.5, 10.6, 10.7, 10.8_

- [ ] 12. Implement main pipeline orchestration
  - [ ] 12.1 Create main pipeline controller (StoryStrengtheningAgent)
    - Wire together all components following reference pattern from workspace-agents/audit/MissingIssuesAuditSystem
    - Implement error handling and stop phrase propagation throughout pipeline
    - Implement loop detection integration at appropriate checkpoints
    - Ensure deterministic processing (same input → same output)
    - Add comprehensive logging following AuditLogger patterns
    - Create configuration class for agent settings
    - _Requirements: All requirements_

  - [ ] 12.2 Write integration tests for complete pipeline
    - Test end-to-end valid issue processing with real-world examples
    - Test end-to-end invalid issue handling with various failure modes
    - Test end-to-end ambiguity handling with unclear requirements
    - Test edge cases (empty issues, very long issues, special characters, markdown edge cases)
    - Test boundary cases (exactly 25 scenarios, exactly 10 questions, threshold boundaries)
    - _Requirements: All requirements_

- [ ] 13. Checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [ ] 14. Add GitHub API integration
  - [ ] 14.1 Create GitHub API client in config package
    - Implement issue reading from GitHub API using existing patterns from workspace-agents/audit/GitHubApiClientWrapper
    - Implement authentication handling with token validation
    - Implement rate limiting following GitHubRateLimiter patterns
    - Implement error handling for API failures (network, auth, rate limit)
    - Add retry logic with exponential backoff
    - _Requirements: 2.1, 2.2, 2.3, 2.4_

  - [ ] 14.2 Write integration tests for GitHub API
    - Test issue reading with mock GitHub API responses
    - Test authentication handling with valid and invalid tokens
    - Test rate limiting behavior with simulated rate limit responses
    - Test error handling for various API failure scenarios
    - _Requirements: 2.1, 2.2, 2.3, 2.4_

- [ ] 15. Add observability and logging
  - [ ] 15.1 Implement logging infrastructure following AuditLogger pattern
    - Log all validation failures with stop phrases and context
    - Log all ambiguities detected with details
    - Track processing time per stage using timestamps
    - Count open questions generated per issue
    - Monitor loop detection triggers with reasons
    - Create structured logging with consistent format
    - Add log levels (INFO, WARN, ERROR) appropriately
    - _Requirements: All requirements_

  - [ ] 15.2 Write tests for logging
    - Test log output for validation failures with various scenarios
    - Test log output for ambiguities with different types
    - Test processing time tracking accuracy
    - Verify log format consistency across all components
    - _Requirements: All requirements_

- [ ] 16. Final Checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

