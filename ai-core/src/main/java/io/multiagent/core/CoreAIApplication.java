
package io.multiagent.core;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@EnableAsync
@SpringBootApplication
public class CoreAIApplication {
    public static void main(String[] args) {
        SpringApplication.run(CoreAIApplication.class, args);
    }
}