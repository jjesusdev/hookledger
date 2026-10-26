package com.jjesusdev.hookledger.support;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import javax.sql.DataSource;
import java.io.IOException;
import java.sql.SQLException;

@TestConfiguration(proxyBeanMethods = false)
public class EmbeddedPostgresTestConfig {

    @Bean(destroyMethod = "close")
    EmbeddedPostgres embeddedPostgres() throws IOException {
        return EmbeddedPostgres.builder().start();
    }

    @Bean
    DataSource dataSource(EmbeddedPostgres postgres) throws SQLException {
        return postgres.getPostgresDatabase();
    }
}
