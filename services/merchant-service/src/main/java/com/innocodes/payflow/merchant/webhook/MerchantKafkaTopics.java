package com.innocodes.payflow.merchant.webhook;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class MerchantKafkaTopics {
    @Bean
    NewTopic merchantWebhookConfigTopic(
            @Value("${payflow.kafka.config.partitions:3}") int partitions,
            @Value("${payflow.kafka.config.replicas:1}") int replicas,
            @Value("${payflow.kafka.merchant-config-topic:merchant-webhook-config}") String topic) {
        return TopicBuilder.name(topic)
                .partitions(partitions)
                .replicas(replicas)
                .config("cleanup.policy", "compact")
                .build();
    }
}
