package com.innocodes.payflow.merchant;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@org.springframework.scheduling.annotation.EnableScheduling
@SpringBootApplication
public class MerchantServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(MerchantServiceApplication.class, args);
    }
}
