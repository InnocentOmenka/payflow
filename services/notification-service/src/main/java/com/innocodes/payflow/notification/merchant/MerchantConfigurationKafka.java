package com.innocodes.payflow.notification.merchant;

import com.innocodes.payflow.notification.exception.NotificationConflictException;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.kafka.config.*;
import org.springframework.kafka.core.*;
import org.springframework.kafka.listener.*;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class MerchantConfigurationKafka {
    public static final String DLT = "merchant-webhook-config-notification-dlt";

    @Bean
    NewTopic merchantConfigDlt(
            @Value("${payflow.kafka.dlt.partitions:3}") int partitions,
            @Value("${payflow.kafka.dlt.replicas:1}") int replicas) {
        return TopicBuilder.name(DLT).partitions(partitions).replicas(replicas).build();
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, String> merchantConfigurationListenerFactory(
            ConsumerFactory<String, String> consumers,
            KafkaTemplate<Object, Object> template,
            @Value("${payflow.kafka.retry.interval-ms:1000}") long interval) {
        var recoverer =
                new DeadLetterPublishingRecoverer(
                        template, (record, error) -> new TopicPartition(DLT, -1));
        recoverer.setFailIfSendResultIsError(true);
        var errors = new DefaultErrorHandler(recoverer, new FixedBackOff(interval, 3));
        errors.addNotRetryableExceptions(
                IllegalArgumentException.class, NotificationConflictException.class);
        errors.setCommitRecovered(true);
        var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
        factory.setConsumerFactory(consumers);
        factory.setCommonErrorHandler(errors);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        return factory;
    }
}
