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
        assertEquals(4, AgentStatus.values().length);
        
        assertNotNull(AgentStatus.SUCCESS);
        assertNotNull(AgentStatus.FAILURE);
        assertNotNull(AgentStatus.STOPPED);
        assertNotNull(AgentStatus.PENDING);
    }

    @Test
    void testEnumValueOf() {
        // Test valueOf with valid status names
        assertEquals(AgentStatus.SUCCESS, AgentStatus.valueOf("SUCCESS"));
        assertEquals(AgentStatus.FAILURE, AgentStatus.valueOf("FAILURE"));
        assertEquals(AgentStatus.STOPPED, AgentStatus.valueOf("STOPPED"));
        assertEquals(AgentStatus.PENDING, AgentStatus.valueOf("PENDING"));
    }

    @Test
    void testEnumValueOfInvalid() {
        // Test valueOf with invalid status name throws exception
        assertThrows(IllegalArgumentException.class, () -> {
            AgentStatus.valueOf("INVALID");
        });
    }

    @Test
    void testEnumName() {
        // Test that enum names are as expected
        assertEquals("SUCCESS", AgentStatus.SUCCESS.name());
        assertEquals("FAILURE", AgentStatus.FAILURE.name());
        assertEquals("STOPPED", AgentStatus.STOPPED.name());
        assertEquals("PENDING", AgentStatus.PENDING.name());
    }

    @Test
    void testEnumEquality() {
        // Test enum equality
        AgentStatus status1 = AgentStatus.SUCCESS;
        AgentStatus status2 = AgentStatus.valueOf("SUCCESS");
        
        assertEquals(status1, status2);
        assertSame(status1, status2); // Enums are singletons
    }

    @Test
    void testEnumInequality() {
        // Test enum inequality
        assertNotEquals(AgentStatus.SUCCESS, AgentStatus.FAILURE);
        assertNotEquals(AgentStatus.PENDING, AgentStatus.STOPPED);
    }

    @Test
    void testEnumOrdinal() {
        // Test enum ordinals (order in which they're declared)
        assertEquals(0, AgentStatus.SUCCESS.ordinal());
        assertEquals(1, AgentStatus.FAILURE.ordinal());
        assertEquals(2, AgentStatus.STOPPED.ordinal());
        assertEquals(3, AgentStatus.PENDING.ordinal());
    }
}
