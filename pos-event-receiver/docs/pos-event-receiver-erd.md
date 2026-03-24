erDiagram
	preregistered_event {
		String id
	}
	event_type {
		UUID id
		String typeCode
		String description
		boolean active
		String apiVersion
		long p50Micros
		long p95Micros
		long p99Micros
	}
	emitted_event {
		UUID eventId
		String id
		String apiVersion
		long timestamp
		long elapsedMs
		Instant publishedAt
	}
