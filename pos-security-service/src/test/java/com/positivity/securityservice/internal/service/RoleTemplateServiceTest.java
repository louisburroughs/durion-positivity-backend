package com.positivity.securityservice.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.positivity.securityservice.internal.entity.Permission;
import com.positivity.securityservice.internal.entity.Role;
import com.positivity.securityservice.internal.enums.LocationHierarchy;
import com.positivity.securityservice.internal.enums.LocationScope;
import com.positivity.securityservice.internal.repository.RoleRepository;
import com.positivity.tenancy.PlatformTenant;
import com.positivity.tenancy.TenantContext;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class RoleTemplateServiceTest {

    private final RoleRepository roles = mock(RoleRepository.class);
    private final RoleTemplateService service = new RoleTemplateService(roles);

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void snapshotsThePlatformTenantsTemplateRoles() {
        Permission view = new Permission();
        view.setName("security:role:view");
        Role admin = new Role();
        admin.setName("ADMIN");
        admin.setTemplateKey("ADMIN");
        admin.setDescription("all-domain");
        admin.setPersonaTitle("platform administrator");
        admin.setMcpPersonaRank((short) 20);
        admin.setLocationScope(LocationScope.ALL);
        admin.setLocationHierarchy(LocationHierarchy.OTHER);
        admin.getPermissions().add(view);
        when(roles.findByTemplateKeyIsNotNullOrderByNameAsc()).thenReturn(List.of(admin));

        List<RoleTemplateEntry> template = TenantContext.callAs(PlatformTenant.ID, service::snapshot);

        assertThat(template).hasSize(1);
        RoleTemplateEntry entry = template.get(0);
        assertThat(entry.templateKey()).isEqualTo("ADMIN");
        assertThat(entry.name()).isEqualTo("ADMIN");
        assertThat(entry.description()).isEqualTo("all-domain");
        assertThat(entry.personaTitle()).isEqualTo("platform administrator");
        assertThat(entry.mcpPersonaRank()).isEqualTo((short) 20);
        assertThat(entry.mcpPersonaEligible()).isTrue();
        assertThat(entry.locationScope()).isEqualTo(LocationScope.ALL);
        assertThat(entry.permissionNames()).containsExactly("security:role:view");
    }

    @Test
    void refusesAnyOtherBinding() {
        TenantContext.bind(UUID.fromString("01990000-0000-7000-8000-000000000123"));
        assertThatThrownBy(service::snapshot).isInstanceOf(IllegalStateException.class);
        TenantContext.clear();
        assertThatThrownBy(service::snapshot).isInstanceOf(RuntimeException.class);
    }
}
