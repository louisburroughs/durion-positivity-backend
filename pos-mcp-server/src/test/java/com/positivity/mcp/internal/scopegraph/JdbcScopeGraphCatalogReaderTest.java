package com.positivity.mcp.internal.scopegraph;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.mcp.internal.scopegraph.ScopeGraphCatalog.ScreenRow;
import com.positivity.mcp.internal.scopegraph.ScopeGraphCatalog.ToolPermissionRow;
import com.positivity.mcp.internal.scopegraph.ScopeGraphCatalog.ToolPrerequisiteRow;
import com.positivity.mcp.internal.scopegraph.ScopeGraphCatalog.ToolRow;
import com.positivity.mcp.internal.scopegraph.ScopeGraphCatalog.ToolWorkflowRow;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * The reader's statements against the catalog tables, with the columns of {@code
 * V1__baseline_mcp_server.sql} that it reads. H2 in PostgreSQL mode: the statements are plain joins.
 */
class JdbcScopeGraphCatalogReaderTest {

    private JdbcTemplate jdbc;

    @BeforeEach
    void schema() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:scope-graph-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("""
                CREATE TABLE mcp_tool (id uuid PRIMARY KEY, name varchar(150) NOT NULL, domain varchar(80) NOT NULL,
                    enabled boolean NOT NULL, source varchar(20) NOT NULL, http_method varchar(10))
                """);
        jdbc.execute("""
                CREATE TABLE mcp_tool_permission (tool_id uuid NOT NULL, permission_code varchar(150) NOT NULL,
                    permission_group text NOT NULL)
                """);
        jdbc.execute("CREATE TABLE mcp_workflow_state (id uuid PRIMARY KEY, name varchar(80) NOT NULL)");
        jdbc.execute("CREATE TABLE mcp_tool_workflow (tool_id uuid NOT NULL, workflow_state_id uuid NOT NULL)");
        jdbc.execute("""
                CREATE TABLE mcp_tool_prerequisite (tool_name varchar(150) NOT NULL, required_param varchar(120) NOT NULL,
                    producing_tool varchar(150) NOT NULL, producing_field varchar(120) NOT NULL)
                """);
        jdbc.execute("""
                CREATE TABLE mcp_screen_registry (id uuid PRIMARY KEY, screen_key varchar(120) NOT NULL,
                    title varchar(200) NOT NULL, description text NOT NULL, domain varchar(80) NOT NULL,
                    url_template text NOT NULL, required_perm varchar(120))
                """);
    }

    @Test
    @DisplayName("an empty catalog reads as an empty catalog")
    void emptyCatalog() {
        assertThat(new JdbcScopeGraphCatalogReader(jdbc).read()).isEqualTo(ScopeGraphCatalog.empty());
    }

    @Test
    @DisplayName("every table is read whole, disabled tools included, joined to tool and state names")
    void readsWholeCatalog() {
        UUID facade = UUID.randomUUID();
        UUID discovered = UUID.randomUUID();
        UUID disabled = UUID.randomUUID();
        UUID idle = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO mcp_tool VALUES (?, 'WorkorderFacadeTool', 'workorder', true, 'facade', NULL)", facade);
        jdbc.update(
                "INSERT INTO mcp_tool VALUES (?, 'workorder_getworkorder', 'workorder', true, 'openapi', 'GET')",
                discovered);
        jdbc.update("INSERT INTO mcp_tool VALUES (?, 'RetiredFacadeTool', 'retired', false, 'facade', NULL)", disabled);
        jdbc.update("INSERT INTO mcp_tool_permission VALUES (?, 'workorder:workorder:view', 'getWorkorder')", facade);
        jdbc.update("INSERT INTO mcp_tool_permission VALUES (?, 'location:read', 'getShopQueue')", facade);
        jdbc.update("INSERT INTO mcp_workflow_state VALUES (?, 'IDLE')", idle);
        jdbc.update("INSERT INTO mcp_tool_workflow VALUES (?, ?)", discovered, idle);
        jdbc.update(
                "INSERT INTO mcp_tool_prerequisite VALUES ('workorder_listwip', 'locationId', 'location_getprimary', 'id')");
        jdbc.update(
                "INSERT INTO mcp_screen_registry VALUES (?, 'workorders.list', 'Work Orders', 'd', 'workorder',"
                        + " '/workorders', 'workorder:workorder:view')",
                UUID.randomUUID());
        jdbc.update(
                "INSERT INTO mcp_screen_registry VALUES (?, 'home', 'Home', 'd', 'master', '/', NULL)",
                UUID.randomUUID());

        ScopeGraphCatalog catalog = new JdbcScopeGraphCatalogReader(jdbc).read();

        assertThat(catalog.tools())
                .containsExactly(
                        new ToolRow("RetiredFacadeTool", "retired", "facade", null, false),
                        new ToolRow("WorkorderFacadeTool", "workorder", "facade", null, true),
                        new ToolRow("workorder_getworkorder", "workorder", "openapi", "GET", true));
        assertThat(catalog.tools().get(2).discovered()).isTrue();
        assertThat(catalog.tools().get(1).discovered()).isFalse();
        assertThat(catalog.toolPermissions())
                .containsExactly(
                        new ToolPermissionRow("WorkorderFacadeTool", "location:read", "getShopQueue"),
                        new ToolPermissionRow("WorkorderFacadeTool", "workorder:workorder:view", "getWorkorder"));
        assertThat(catalog.toolWorkflowStates()).containsExactly(new ToolWorkflowRow("workorder_getworkorder", "IDLE"));
        assertThat(catalog.toolPrerequisites())
                .containsExactly(new ToolPrerequisiteRow("workorder_listwip", "locationId", "location_getprimary"));
        assertThat(catalog.screens())
                .containsExactly(
                        new ScreenRow("home", "master", null, "/", "Home"),
                        new ScreenRow(
                                "workorders.list",
                                "workorder",
                                "workorder:workorder:view",
                                "/workorders",
                                "Work Orders"));
    }
}
