package com.transactionwatch;

import com.transactionwatch.rules.RuleProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(RuleProperties.class)
public class TransactionWatchApplication {
    public static void main(String[] args) {
        SpringApplication.run(TransactionWatchApplication.class, args);
    }
}
