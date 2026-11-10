package com.innocodes.payflow.notification.delivery;

import com.innocodes.payflow.notification.config.WebhookProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;
import java.net.InetAddress;
import java.net.URI;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WebhookSecurityTest {
    private final DestinationPolicy production = new DestinationPolicy(properties(false), new MockEnvironment());

    static WebhookProperties properties(boolean local) {
        return new WebhookProperties(3, Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(2),
                Duration.ofSeconds(5), Duration.ofMillis(100), Duration.ofSeconds(1), local, Map.of());
    }

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "0.0.0.0", "10.1.2.3", "172.16.1.1", "192.168.1.1",
            "169.254.169.254", "100.100.100.200", "192.0.2.1", "198.18.1.1", "198.51.100.1",
            "203.0.113.1", "224.0.0.1", "240.0.0.1", "::1", "::", "fe80::1", "fc00::1",
            "2001:db8::1", "2002:7f00:1::", "2001::1", "::ffff:127.0.0.1"})
    void shouldRejectNonPublicAddresses(String address) throws Exception {
        assertThrows(IllegalArgumentException.class, () -> production.validateAddress(InetAddress.getByName(address)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://example.com/hook", "https://user:secret@example.com/hook", "file:///etc/passwd",
            "https://example.com/hook#fragment", "https://example.com:444/hook"})
    void shouldRejectUnsafeUri(String uri) {
        assertThrows(IllegalArgumentException.class, () -> production.validateUri(URI.create(uri)));
    }

    @Test
    void shouldPermitPublicHttpsAddresses() throws Exception {
        production.validateUri(URI.create("https://example.com/webhook"));
        production.validateAddress(InetAddress.getByName("8.8.8.8"));
        production.validateAddress(InetAddress.getByName("2001:4860:4860::8888"));
    }

    @Test
    void shouldRequireExplicitDevProfileForLocalAllowance() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> new DestinationPolicy(properties(true), new MockEnvironment()));
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("dev");
        var local = new DestinationPolicy(properties(true), env);
        local.validateUri(URI.create("http://localhost:1234/webhook"));
        local.validateAddress(InetAddress.getLoopbackAddress());
        assertThrows(IllegalArgumentException.class, () -> local.validateAddress(InetAddress.getByName("10.0.0.1")));
    }

    @Test
    void shouldSignExactTimestampAndBody() {
        assertEquals("v1=278a86bed9ce4cbb84d0009a52891fec7342ef75b40cbcb81eb04ea5bb4c57ae", WebhookSigner.sign("0123456789abcdef0123456789abcdef",
                "1700000000", "{\"eventId\":\"example\"}"));
        assertNotEquals(WebhookSigner.sign("secret", "1", "a"), WebhookSigner.sign("secret", "1", "a "));
    }

    @Test
    void shouldUseBoundedExponentialBackoffWithJitter() {
        var policy = new RetryPolicy(properties(false));
        assertEquals(50, policy.delayMillis(1, 0));
        assertEquals(100, policy.delayMillis(2, 0));
        assertEquals(500, policy.delayMillis(100, 0));
        for (int i = 0; i < 100; i++) {
            assertTrue(policy.delayMillis(1) >= 50 && policy.delayMillis(1) <= 100);
            assertTrue(policy.delayMillis(100) >= 500 && policy.delayMillis(100) <= 1000);
        }
    }

    @Test
    void shouldRedactSecretsInConfigurationAndDestination() {
        var merchant = new WebhookProperties.Merchant(URI.create("https://example.com"), "sensitive");
        assertFalse(merchant.toString().contains("sensitive"));
        assertFalse(new MerchantWebhookDestinationResolver.Destination(merchant.url(), merchant.secret())
                .toString().contains("sensitive"));
    }
}
