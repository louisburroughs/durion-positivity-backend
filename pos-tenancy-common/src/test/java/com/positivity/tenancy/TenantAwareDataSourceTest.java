package com.positivity.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The statements the datasource issues, without a database. {@link TenantAwareDataSourceIT} proves
 * the isolation those statements actually buy; this proves they are issued at the right moments,
 * fast enough to run in the unit suite.
 */
@DisplayName("TenantAwareDataSource statements")
class TenantAwareDataSourceTest {

    private static final UUID TENANT = UUID.fromString("01900000-0000-7000-8000-00000000000a");
    private static final UUID FALLBACK = UUID.fromString("01900000-0000-7000-8000-00000000000f");

    private DataSource delegate;
    private Connection connection;
    private List<String> sql;
    private List<String> parameters;

    @BeforeEach
    void setUp() throws SQLException {
        delegate = mock(DataSource.class);
        connection = mock(Connection.class);
        sql = new ArrayList<>();
        parameters = new ArrayList<>();
        when(delegate.getConnection()).thenReturn(connection);
        when(connection.isClosed()).thenReturn(false);
        when(connection.prepareStatement(anyString())).thenAnswer(invocation -> {
            sql.add(invocation.getArgument(0));
            PreparedStatement statement = mock(PreparedStatement.class);
            when(statement.execute()).thenReturn(true);
            org.mockito.Mockito.doAnswer(set -> {
                        parameters.add(set.getArgument(1));
                        return null;
                    })
                    .when(statement)
                    .setString(org.mockito.ArgumentMatchers.anyInt(), anyString());
            return statement;
        });
    }

    @AfterEach
    void unbind() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("binds the bound tenant on checkout")
    void bindsOnCheckout() throws SQLException {
        TenantContext.bind(TENANT);
        try (Connection ignored = new TenantAwareDataSource(delegate, null).getConnection()) {
            assertThat(sql).first().asString().contains("set_config('app.current_tenant'");
            assertThat(parameters).containsExactly(TENANT.toString());
        }
    }

    @Test
    @DisplayName("clears the setting on checkout when nothing is bound, rather than leaving it")
    void clearsWhenUnbound() throws SQLException {
        try (Connection ignored = new TenantAwareDataSource(delegate, null).getConnection()) {
            // A pooled connection may still carry the previous borrower's tenant.
            assertThat(sql).first().asString().contains("set_config('app.current_tenant', '', false)");
            assertThat(parameters).isEmpty();
        }
    }

    @Test
    @DisplayName("uses the fallback tenant only when nothing is bound")
    void usesFallbackOnlyWhenUnbound() throws SQLException {
        try (Connection ignored = new TenantAwareDataSource(delegate, FALLBACK).getConnection()) {
            assertThat(parameters).containsExactly(FALLBACK.toString());
        }

        sql.clear();
        parameters.clear();
        TenantContext.bind(TENANT);
        try (Connection ignored = new TenantAwareDataSource(delegate, FALLBACK).getConnection()) {
            assertThat(parameters).containsExactly(TENANT.toString());
        }
    }

    @Test
    @DisplayName("resets the setting before the connection returns to the pool")
    void resetsOnClose() throws SQLException {
        TenantContext.bind(TENANT);
        Connection borrowed = new TenantAwareDataSource(delegate, null).getConnection();
        sql.clear();

        borrowed.close();

        assertThat(sql).anySatisfy(s -> assertThat(s).contains("set_config('app.current_tenant', '', false)"));
        verify(connection).close();
    }

    @Test
    @DisplayName("a connection that cannot be bound is returned to the pool, not leaked")
    void closesTheConnectionWhenBindingFails() throws SQLException {
        when(connection.prepareStatement(anyString())).thenThrow(new SQLException("no session"));
        TenantContext.bind(TENANT);

        assertThatThrownBy(() -> new TenantAwareDataSource(delegate, null).getConnection())
                .isInstanceOf(SQLException.class);
        verify(connection).close();
    }

    @Test
    @DisplayName("delegates other calls through to the real connection")
    void delegatesOtherCalls() throws SQLException {
        TenantContext.bind(TENANT);
        try (Connection borrowed = new TenantAwareDataSource(delegate, null).getConnection()) {
            borrowed.setAutoCommit(false);
            verify(connection).setAutoCommit(false);
            verify(connection, never()).close();
        }
    }
}
