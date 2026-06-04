package io.multiagent.notefrais.config;

import io.multiagent.notefrais.expense.repository.ExpenseWeaviateRepository;
import io.multiagent.notefrais.expense.service.ExpenseIdGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class IdGeneratorConfig {

    @Bean
    public ExpenseIdGenerator expenseIdGenerator(ExpenseWeaviateRepository expenseRepo) {
        return new ExpenseIdGenerator(expenseRepo);
    }
}
