package com.innocodes.payflow.notification.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicConfig {

    public static final String PAYMENT_PROCESSED_TOPIC = "payment-processed";
    public static final String PAYMENT_PROCESSED_DLT = "payment-processed-notification-dlt";

    @Bean
    public NewTopic paymentProcessedNotificationDlt(
            @Value("${payflow.kafka.dlt.partitions:3}") int partitions,
            @Value("${payflow.kafka.dlt.replicas:1}") int replicas
    ) {
        return TopicBuilder.name(PAYMENT_PROCESSED_DLT).partitions(partitions).replicas(replicas).build();
    }
}
