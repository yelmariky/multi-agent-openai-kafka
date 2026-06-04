package io.multiagent.invoice.schema;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Schema initializer — no-op after Weaviate → pgvector migration.
 * PostgreSQL schema is managed via Flyway/Liquibase or manual DDL.
 */
@Slf4j
@Component
public class InvoiceSchemaInitializer implements ApplicationRunner {

    @Override
    public void run(ApplicationArguments args) {
        log.info("InvoiceSchemaInitializer: pgvector mode — schema managed externally, nothing to initialize.");
    }
}
