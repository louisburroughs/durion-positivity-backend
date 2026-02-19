package com.positivity.people.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.people.internal.dto.BreakDto;
import com.positivity.people.internal.dto.WorkSessionDto;
import com.positivity.people.internal.exception.WorkSessionNotFoundException;
import com.positivity.people.internal.repository.WorkSessionBreakRepository;
import com.positivity.people.internal.repository.WorkSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.datasource.url=jdbc:h2:mem:work-session-service-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "eureka.client.enabled=false",
        "pos.security.permission-registration.enabled=false"
})
@ActiveProfiles("test")
class WorkSessionServiceTest {

    @Autowired
    private WorkSessionService workSessionService;
    @Autowired
    private WorkSessionRepository workSessionRepository;
    @Autowired
    private WorkSessionBreakRepository workSessionBreakRepository;

    private String personId;
    private String actor;

    @BeforeEach
    void setUp() {
        workSessionBreakRepository.deleteAll();
        workSessionRepository.deleteAll();
        personId = "person-1001";
        actor = "manager.user";
    }

    @Test
    void startSession_whenNoActiveSessionExists_createsActiveSession() {
        WorkSessionDto result = workSessionService.startSession(personId);

        assertThat(result).isNotNull();
        assertThat(result.getSessionId()).isNotNull();
        assertThat(result.getPersonId()).isEqualTo(personId);
        assertThat(result.getStatus()).isEqualTo("ACTIVE");
        assertThat(result.getStartedAt()).isNotNull();
    }

    @Test
    void startSession_whenActiveSessionAlreadyExists_throwsException() {
        workSessionService.startSession(personId);

        assertThatThrownBy(() -> workSessionService.startSession(personId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("active session");
    }

    @Test
    void stopSession_whenActiveSessionExists_endsSession() {
        workSessionService.startSession(personId);

        WorkSessionDto result = workSessionService.stopSession(personId);

        assertThat(result).isNotNull();
        assertThat(result.getPersonId()).isEqualTo(personId);
        assertThat(result.getStatus()).isEqualTo("ENDED");
        assertThat(result.getEndedAt()).isNotNull();
    }

    @Test
    void stopSession_whenNoActiveSessionExists_throwsWorkSessionNotFoundException() {
        assertThatThrownBy(() -> workSessionService.stopSession(personId))
                .isInstanceOf(WorkSessionNotFoundException.class);
    }

    @Test
    void startBreak_whenSessionExistsAndNoActiveBreak_createsActiveBreak() {
        WorkSessionDto session = workSessionService.startSession(personId);

        BreakDto result = workSessionService.startBreak(session.getSessionId());

        assertThat(result).isNotNull();
        assertThat(result.getSessionId()).isEqualTo(session.getSessionId());
        assertThat(result.getStartedAt()).isNotNull();
        assertThat(result.getEndedAt()).isNull();
    }

    @Test
    void startBreak_whenSessionDoesNotExist_throwsWorkSessionNotFoundException() {
        assertThatThrownBy(() -> workSessionService.startBreak(999L))
                .isInstanceOf(WorkSessionNotFoundException.class);
    }

    @Test
    void startBreak_whenBreakAlreadyActive_throwsException() {
        WorkSessionDto session = workSessionService.startSession(personId);
        workSessionService.startBreak(session.getSessionId());

        assertThatThrownBy(() -> workSessionService.startBreak(session.getSessionId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already active");
    }

    @Test
    void stopBreak_whenActiveBreakExists_endsBreak() {
        WorkSessionDto session = workSessionService.startSession(personId);
        workSessionService.startBreak(session.getSessionId());

        BreakDto result = workSessionService.stopBreak(session.getSessionId());

        assertThat(result).isNotNull();
        assertThat(result.getSessionId()).isEqualTo(session.getSessionId());
        assertThat(result.getEndedAt()).isNotNull();
    }

    @Test
    void stopBreak_whenNoActiveBreakExists_throwsException() {
        WorkSessionDto session = workSessionService.startSession(personId);

        assertThatThrownBy(() -> workSessionService.stopBreak(session.getSessionId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No active break");
    }
}
