package com.juzo.ai.ordermanager.account.config;

import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

public class RlsTransactionManager extends DataSourceTransactionManager {

    public RlsTransactionManager(DataSource dataSource) {
        super(dataSource);
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
        super.doBegin(transaction, definition);

        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth != null && auth.getPrincipal() instanceof Jwt jwt)) {
            return;
        }

        ConnectionHolder holder = (ConnectionHolder) TransactionSynchronizationManager
                .getResource(obtainDataSource());
        Connection con = holder.getConnection();

        try (PreparedStatement ps = con.prepareStatement("SELECT set_config('app.current_user_id', ?, true)")) {
            ps.setString(1, jwt.getSubject());
            ps.execute();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to set RLS session context", e);
        }
    }
}
