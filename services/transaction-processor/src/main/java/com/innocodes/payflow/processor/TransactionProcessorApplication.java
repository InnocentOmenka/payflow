package com.innocodes.payflow.processor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class TransactionProcessorApplication {

    public static void main(String[] args) {
        SpringApplication.run(TransactionProcessorApplication.class, args);
    }
}