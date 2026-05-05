package com.juzo.ai.ordermanager.account.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

@Configuration
public class DataConfig {

    @Bean
    public PlatformTransactionManager transactionManager(DataSource dataSource) {
        return new RlsTransactionManager(dataSource);
    }
}
