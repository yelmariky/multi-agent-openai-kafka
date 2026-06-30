package io.multiagent.expense.config;

import io.multiagent.expense.service.ExpenseDataService;
import io.multiagent.expense.service.ExpenseIdGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class IdGeneratorConfig {

    @Bean
    public ExpenseIdGenerator expenseIdGenerator(ExpenseDataService expenseDataService) {
        return new ExpenseIdGenerator(expenseDataService);
    }
}
