package com.positivity.mcp.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.mcp.internal.repository.ToolMetadataRepository;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ToolPermissionAdminServiceTest {

    private static final UUID TOOL_ID = UUID.fromString("00000000-0000-7000-8000-000000000901");
    private static final String TOOL = "event-receiver_getactiveeventtypes";

    @Mock
    private ToolMetadataRepository repository;

    private ToolPermissionAdminService service() {
        return new ToolPermissionAdminService(repository);
    }

    @Test
    void grant_addsPermissionAndReturnsFullSet() {
        when(repository.findDiscoveredToolIdByName(TOOL)).thenReturn(Optional.of(TOOL_ID));
        when(repository.listToolPermissions(TOOL_ID))
                .thenReturn(List.of("AUTHENTICATED", "event-receiver:event_type:view"));

        var response = service().grantPermission(TOOL, "event-receiver:event_type:view");

        verify(repository).addToolPermission(TOOL_ID, "event-receiver:event_type:view");
        assertThat(response.toolName()).isEqualTo(TOOL);
        assertThat(response.permissionCodes()).containsExactly("AUTHENTICATED", "event-receiver:event_type:view");
    }

    @Test
    void revoke_removesPermission() {
        when(repository.findDiscoveredToolIdByName(TOOL)).thenReturn(Optional.of(TOOL_ID));

        service().revokePermission(TOOL, "AUTHENTICATED");

        verify(repository).removeToolPermission(TOOL_ID, "AUTHENTICATED");
    }

    @Test
    void list_returnsGrantedCodes() {
        when(repository.findDiscoveredToolIdByName(TOOL)).thenReturn(Optional.of(TOOL_ID));
        when(repository.listToolPermissions(TOOL_ID)).thenReturn(List.of("AUTHENTICATED"));

        assertThat(service().listPermissions(TOOL).permissionCodes()).containsExactly("AUTHENTICATED");
    }

    @Test
    void unknownTool_throwsNotFound() {
        when(repository.findDiscoveredToolIdByName("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().listPermissions("nope"))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("nope");
    }
}
