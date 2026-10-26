package io.multiagent.core.config;

import io.multiagent.core.i18n.Messages;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.annotation.EnableAsync;

import java.util.Locale;

@Configuration
@EnableAsync
public class AsyncConfig {

    /** Propage la langue de la requête aux méthodes @Async (messages d'erreur de l'upload de justificatif). */
    @Bean
    public TaskDecorator localeTaskDecorator(Messages messages) {
        return task -> {
            Locale locale = messages.locale();
            return () -> {
                LocaleContextHolder.setLocale(locale);
                try {
                    task.run();
                } finally {
                    LocaleContextHolder.resetLocaleContext();
                }
            };
        };
    }
}
