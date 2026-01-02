package com.pos.agent.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for AgentStatus enum
 */
class AgentStatusTest {

    @Test
    void testEnumValues() {
        // Verify all expected enum values exist
        assertEquals(4, AgentProcessingState.values().length);

        assertNotNull(AgentProcessingState.SUCCESS);
        assertNotNull(AgentProcessingState.FAILURE);
        assertNotNull(AgentProcessingState.STOPPED);
        assertNotNull(AgentProcessingState.PENDING);
    }

    @Test
    void testEnumValueOf() {
        // Test valueOf with valid status names
        assertEquals(AgentProcessingState.SUCCESS, AgentProcessingState.valueOf("SUCCESS"));
        assertEquals(AgentProcessingState.FAILURE, AgentProcessingState.valueOf("FAILURE"));
        assertEquals(AgentProcessingState.STOPPED, AgentProcessingState.valueOf("STOPPED"));
        assertEquals(AgentProcessingState.PENDING, AgentProcessingState.valueOf("PENDING"));
    }

    @Test
    void testEnumValueOfInvalid() {
        // Test valueOf with invalid status name throws exception
        assertThrows(IllegalArgumentException.class, () -> {
            AgentProcessingState.valueOf("INVALID");
        });
    }

    @Test
    void testEnumName() {
        // Test that enum names are as expected
        assertEquals("SUCCESS", AgentProcessingState.SUCCESS.name());
        assertEquals("FAILURE", AgentProcessingState.FAILURE.name());
        assertEquals("STOPPED", AgentProcessingState.STOPPED.name());
        assertEquals("PENDING", AgentProcessingState.PENDING.name());
    }

    @Test
    void testEnumEquality() {
        // Test enum equality
        AgentProcessingState status1 = AgentProcessingState.SUCCESS;
        AgentProcessingState status2 = AgentProcessingState.valueOf("SUCCESS");

        assertEquals(status1, status2);
        assertSame(status1, status2); // Enums are singletons
    }

    @Test
    void testEnumInequality() {
        // Test enum inequality
        assertNotEquals(AgentProcessingState.SUCCESS, AgentProcessingState.FAILURE);
        assertNotEquals(AgentProcessingState.PENDING, AgentProcessingState.STOPPED);
    }

    @Test
    void testEnumOrdinal() {
        // Test enum ordinals (order in which they're declared)
        assertEquals(0, AgentProcessingState.SUCCESS.ordinal());
        assertEquals(1, AgentProcessingState.FAILURE.ordinal());
        assertEquals(2, AgentProcessingState.STOPPED.ordinal());
        assertEquals(3, AgentProcessingState.PENDING.ordinal());
    }
}
