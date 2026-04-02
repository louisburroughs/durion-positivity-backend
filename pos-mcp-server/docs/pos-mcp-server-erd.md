erDiagram
	system_prompt {
		UUID id
		String name
		TEXT content
		OffsetDateTime createdAt
		OffsetDateTime updatedAt
	}
	nlti_session {
		UUID id
		String subjectId
		OffsetDateTime createdAt
		OffsetDateTime updatedAt
	}
	nlti_request {
		UUID id
		UUID correlationId
		UUID sessionId
		String status
		String promptHash
		OffsetDateTime createdAt
	}
	nlti_intent {
		UUID id
		UUID requestId
		UUID sessionId
		UUID correlationId
		String intentType
		String status
		String riskLevel
		TEXT slotsJson
		TEXT clarificationQuestionsJson
		OffsetDateTime createdAt
		OffsetDateTime updatedAt
	}
	nlti_audit_event {
		UUID id
		UUID correlationId
		UUID sessionId
		UUID requestId
		String eventType
		OffsetDateTime timestamp
		String actorSubjectId
		String payloadRef
		String payloadHash
		OffsetDateTime createdAt
	}
	llm_api_config {
		UUID id
		String apiId
		String model
		String baseUrl
		String apiKey
		OffsetDateTime createdAt
		OffsetDateTime updatedAt
	}
