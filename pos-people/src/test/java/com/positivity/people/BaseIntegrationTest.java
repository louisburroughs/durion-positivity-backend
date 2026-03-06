package com.positivity.people;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = { "spring.datasource.url=jdbc:h2:mem:people-access;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
				"spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa",
				"spring.datasource.password=", "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
				"spring.jpa.hibernate.ddl-auto=create-drop", "spring.flyway.enabled=false",
				"eureka.client.enabled=false", "pos.security.permission-registration.enabled=false" })
@ActiveProfiles("test")
public abstract class BaseIntegrationTest {

	@Autowired
	protected WebApplicationContext webApplicationContext;

	@Autowired
	protected ObjectMapper objectMapper;

	@Autowired
	protected JdbcTemplate jdbcTemplate;

	protected MockMvc mockMvc;

	protected static final String TEST_USER = "integration-test-user";

	protected static final String TEST_AUTHORITIES = String.join(",",
			"people:role:view",
			"people:role:assign",
			"people:role:revoke",
			"people:person:view",
			"people:person:create",
			"people:person:edit",
			"people:person:delete",
			"people:employee:create",
			"people:employee:edit",
			"people:employee:view",
			"people:employee:deactivate",
			"people:userLink:write",
			"people:userLink:view",
			"people:availability:view",
			"people:timeAdjustment:create",
			"people:timeAdjustment:view",
			"people:timeAdjustment:approve",
			"people:timeEntry:approve",
			"people:timeEntry:reject",
			"people:timeException:create",
			"people:timeException:view",
			"people:timeException:acknowledge",
			"people:timeException:resolve",
			"people:time:export:read");

	protected static final String TEST_CORRELATION_ID = "people-test-correlation-id";

	@BeforeEach
	void setUpMockMvc() {
		resetDatabaseState();
		this.mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(springSecurity()).build();
	}

	private void resetDatabaseState() {
		jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY FALSE");
		try {
			jdbcTemplate.queryForList(
					"SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = 'PUBLIC' AND TABLE_TYPE = 'BASE TABLE'",
					String.class).forEach(table -> jdbcTemplate.execute("TRUNCATE TABLE \"" + table + "\""));
		}
		finally {
			jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY TRUE");
		}
	}

	protected MockHttpServletRequestBuilder withAuth(MockHttpServletRequestBuilder builder) {
		return builder.header("X-User", TEST_USER)
			.header("X-Authorities", TEST_AUTHORITIES)
			.header("X-Correlation-Id", TEST_CORRELATION_ID);
	}

	protected MockHttpServletRequestBuilder withAuth(MockHttpServletRequestBuilder builder, String authorities) {
		return builder.header("X-User", TEST_USER)
			.header("X-Authorities", authorities)
			.header("X-Correlation-Id", TEST_CORRELATION_ID);
	}

}
