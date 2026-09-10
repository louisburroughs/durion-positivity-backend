package com.positivity.tenancy.datasource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.tenancy.TenancyProperties;
import com.positivity.tenancy.TenantContext;
import com.positivity.tenancy.TenantResolver;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class TenantAwareDataSourceTest {

    private static final UUID A = UUID.fromString("01900000-0000-7000-8000-000000000001");

    private final DataSource target = mock(DataSource.class);
    private final Connection raw = mock(Connection.class);
    private final DatabaseMetaData metaData = mock(DatabaseMetaData.class);
    private final PreparedStatement bindStatement = mock(PreparedStatement.class);
    private final Statement resetStatement = mock(Statement.class);

    @BeforeEach
    void wire() throws SQLException {
        when(target.getConnection()).thenReturn(raw);
        when(raw.getMetaData()).thenReturn(metaData);
        when(metaData.getDatabaseProductName()).thenReturn("PostgreSQL");
        when(raw.prepareStatement(TenantAwareDataSource.BIND_SQL)).thenReturn(bindStatement);
        when(raw.createStatement()).thenReturn(resetStatement);
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private TenantAwareDataSource dataSource() {
        return new TenantAwareDataSource(target, new TenantResolver(new TenancyProperties()));
    }

    @Test
    void bindsTheTenantOnCheckoutAndResetsOnClose() throws Exception {
        TenantContext.bind(A);

        Connection connection = dataSource().getConnection();
        connection.close();

        InOrder order = inOrder(bindStatement, resetStatement, raw);
        order.verify(bindStatement).setString(1, A.toString());
        order.verify(bindStatement).execute();
        order.verify(resetStatement).execute(TenantAwareDataSource.RESET_SQL);
        order.verify(raw).close();
    }

    @Test
    void resetsOnCheckoutWhenNothingResolves() throws Exception {
        dataSource().getConnection();

        verify(resetStatement).execute(TenantAwareDataSource.RESET_SQL);
        verify(raw, never()).prepareStatement(anyString());
    }

    @Test
    void closesTheRawConnectionWhenBindingFails() throws Exception {
        TenantContext.bind(A);
        when(bindStatement.execute()).thenThrow(new SQLException("no such function"));

        assertThatThrownBy(() -> dataSource().getConnection()).isInstanceOf(SQLException.class);
        verify(raw).close();
    }

    @Test
    void passesThroughOnAnythingButPostgres() throws Exception {
        when(metaData.getDatabaseProductName()).thenReturn("H2");
        TenantContext.bind(A);

        Connection connection = dataSource().getConnection();

        assertThat(connection).isSameAs(raw);
        verify(raw, never()).prepareStatement(anyString());
        verify(raw, never()).createStatement();
    }

    @Test
    void proxyDelegatesAndUnwraps() throws Exception {
        when(raw.isWrapperFor(Connection.class)).thenReturn(true);
        when(raw.unwrap(Connection.class)).thenReturn(raw);
        when(raw.getAutoCommit()).thenReturn(true);

        Connection connection = dataSource().getConnection();

        assertThat(connection).isNotSameAs(raw);
        assertThat(connection.getAutoCommit()).isTrue();
        assertThat(connection.isWrapperFor(Connection.class)).isTrue();
        assertThat(connection.unwrap(Connection.class)).isSameAs(connection);
        assertThat(connection).isEqualTo(connection).hasSameHashCodeAs(connection);
    }
}
