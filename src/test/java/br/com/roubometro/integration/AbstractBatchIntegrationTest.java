package br.com.roubometro.integration;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;

@SpringBootTest
@ActiveProfiles("test")
public abstract class AbstractBatchIntegrationTest {

    // Singleton container shared across all integration test classes
    static final MySQLContainer<?> mysql;

    static {
        mysql = new MySQLContainer<>("mysql:8.0")
                .withDatabaseName("roubometro")
                .withUsername("roubometro")
                .withPassword("roubometro")
                .withInitScript("init-db/api_schema_and_seed.sql");
        mysql.start();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }
}
