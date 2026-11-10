package com.innocodes.payflow.notification.delivery;

import com.innocodes.payflow.notification.config.WebhookProperties;
import com.innocodes.payflow.notification.model.Notification;
import jakarta.annotation.PreDestroy;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.util.Timeout;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.time.Instant;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public class WebhookHttpClient implements AutoCloseable {
    private final MerchantWebhookDestinationResolver resolver;
    private final DestinationPolicy policy;
    private final WebhookProperties properties;
    private final ExecutorService requests = Executors.newVirtualThreadPerTaskExecutor();

    public WebhookHttpClient(MerchantWebhookDestinationResolver resolver, DestinationPolicy policy,
                             WebhookProperties properties) {
        this.resolver = resolver;
        this.policy = policy;
        this.properties = properties;
    }

    public DeliveryResult deliver(Notification notification) {
        long start = System.nanoTime();
        MerchantWebhookDestinationResolver.Destination destination;
        try {
            destination = resolver.resolve(notification.destinationRef());
        } catch (com.innocodes.payflow.notification.merchant.DestinationUnavailableException exception) {
            return failure(start,exception.getMessage(),exception.retryable());
        } catch (IllegalStateException exception) {
            return failure(start, "DESTINATION_NOT_CONFIGURED", true);
        }
        HttpPost post;
        try {
            policy.validateUri(destination.url());
            post = new HttpPost(destination.url());
        } catch (IllegalArgumentException exception) {
            return failure(start, "UNSAFE_DESTINATION", false);
        }
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        post.setHeader("PayFlow-Webhook-Id", notification.eventId().toString());
        post.setHeader("PayFlow-Webhook-Timestamp", timestamp);
        post.setHeader("PayFlow-Webhook-Signature", WebhookSigner.sign(destination.secret(), timestamp, notification.webhookPayload()));
        if(destination.secretReference()!=null) {
            post.setHeader("PayFlow-Webhook-Key-Id",destination.secretReference().toString());
            post.setHeader("PayFlow-Webhook-Key-Version",destination.secretVersion().toString());
        }
        post.setEntity(new StringEntity(notification.webhookPayload(), ContentType.APPLICATION_JSON));
        var task = requests.submit(() -> send(post, destination));
        try {
            int status = task.get(properties.requestTimeout().toMillis(), TimeUnit.MILLISECONDS);
            boolean success = status >= 200 && status < 300;
            return new DeliveryResult(status, elapsed(start), success ? null : "HTTP_" + status,
                    status == 408 || status == 429 || status >= 500);
        } catch (TimeoutException exception) {
            post.cancel();
            task.cancel(true);
            return failure(start, "TIMEOUT", true);
        } catch (InterruptedException exception) {
            post.cancel();
            task.cancel(true);
            Thread.currentThread().interrupt();
            return failure(start, "INTERRUPTED", true);
        } catch (ExecutionException exception) {
            return failure(start, exception.getCause() instanceof IllegalArgumentException
                    ? "UNSAFE_DESTINATION" : "NETWORK_ERROR", !(exception.getCause() instanceof IllegalArgumentException));
        }
    }

    @SuppressWarnings("deprecation")
    private int send(HttpPost post, MerchantWebhookDestinationResolver.Destination destination) throws Exception {
        // Validate ALL resolved addresses and pin them into the socket resolver. This closes
        // the DNS-rebinding gap between a URL precheck and an independent client DNS lookup.
        InetAddress[] addresses = policy.resolve(destination.url().getHost());
        if ("http".equalsIgnoreCase(destination.url().getScheme())) {
            for (var address : addresses) {
                if (!address.isLoopbackAddress()) {
                    throw new IllegalArgumentException("Plain HTTP is only allowed for local dev receivers");
                }
            }
        }
        if (post.isCancelled()) {
            throw new java.io.InterruptedIOException("Request cancelled before connect");
        }
        DnsResolver pinned = new DnsResolver() {
            @Override
            public InetAddress[] resolve(String host) {
                return addresses.clone();
            }
            @Override
            public String resolveCanonicalHostname(String host) {
                return host;
            }
        };
        var manager = PoolingHttpClientConnectionManagerBuilder.create().setDnsResolver(pinned)
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(Timeout.ofMilliseconds(properties.connectTimeout().toMillis()))
                        .setSocketTimeout(Timeout.ofMilliseconds(properties.responseTimeout().toMillis())).build())
                .build();
        try (var client = HttpClients.custom().setConnectionManager(manager)
                .disableRedirectHandling().disableAutomaticRetries().disableCookieManagement()
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setResponseTimeout(Timeout.ofMilliseconds(properties.responseTimeout().toMillis())).build())
                .build()) {
            var response = client.execute(post);
            try {
                return response.getCode();
            } finally {
                // Do not read, buffer, log, or drain untrusted response bodies.
                response.close(CloseMode.IMMEDIATE);
            }
        }
    }

    private DeliveryResult failure(long start, String reason, boolean retryable) {
        return new DeliveryResult(null, elapsed(start), reason, retryable);
    }

    private long elapsed(long start) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    }

    @PreDestroy
    @Override
    public void close() {
        requests.shutdownNow();
    }
}
