package com.innocodes.payflow.payment.merchant;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.client.*;
import org.springframework.security.oauth2.client.endpoint.RestClientClientCredentialsTokenResponseClient;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.client.registration.*;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/** Machine tokens may be reused until expiry; membership decisions are never cached. */
@Component
public class MerchantAccessClient {
    private final RestClient http;
    private final OAuth2AuthorizedClientManager tokens;

    public MerchantAccessClient(@Value("${payflow.merchant-access.base-url}") String baseUrl,
            @Value("${payflow.merchant-access.token-uri}") String tokenUri,
            @Value("${payflow.merchant-access.client-secret:}") String secret) {
        var transport = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build());
        transport.setReadTimeout(Duration.ofSeconds(3));
        http = RestClient.builder().baseUrl(baseUrl).requestFactory(transport).build();
        if (secret.isBlank()) {
            tokens = null; // Missing local credentials cannot grant access; core workers can still start.
        } else {
            var registration = ClientRegistration.withRegistrationId("merchant-resolver")
                    .clientId("payflow-payment-service").clientSecret(secret)
                    .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                    .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                    .scope("merchant.access.resolve").tokenUri(tokenUri).build();
            var registrations = new InMemoryClientRegistrationRepository(registration);
            var tokenResponse = new RestClientClientCredentialsTokenResponseClient();
            tokenResponse.setRestClient(RestClient.builder().requestFactory(transport)
                    .configureMessageConverters(converters -> converters.disableDefaults()
                            .addCustomConverter(new FormHttpMessageConverter())
                            .addCustomConverter(new OAuth2AccessTokenResponseHttpMessageConverter()))
                    .defaultStatusHandler(new OAuth2ErrorResponseErrorHandler()).build());
            var provider = new ClientCredentialsOAuth2AuthorizedClientProvider();
            provider.setAccessTokenResponseClient(tokenResponse);
            var manager = new AuthorizedClientServiceOAuth2AuthorizedClientManager(registrations,
                    new InMemoryOAuth2AuthorizedClientService(registrations));
            manager.setAuthorizedClientProvider(provider);
            tokens = manager;
        }
    }

    public Access resolve(String issuer, String subject) {
        if (tokens == null) throw new AuthorizationUnavailableException();
        String token;
        try {
            var client = tokens.authorize(OAuth2AuthorizeRequest.withClientRegistrationId("merchant-resolver")
                    .principal("payflow-payment-service").build());
            if (client == null) throw new AuthorizationUnavailableException();
            token = client.getAccessToken().getTokenValue();
        } catch (RuntimeException unavailable) {
            throw new AuthorizationUnavailableException(); // Never propagate token endpoint bodies/credentials.
        }
        Access access;
        try {
            access = http.post().uri("/internal/v1/merchant-access/resolve")
                    .headers(headers -> headers.setBearerAuth(token)).contentType(MediaType.APPLICATION_JSON)
                    .body(new Principal(issuer, subject)).retrieve()
                    .onStatus(status -> status.is3xxRedirection() || (status.is2xxSuccessful() && status.value() != 200),
                            (request, response) -> { throw new AuthorizationUnavailableException(); })
                    .body(Access.class);
        } catch (RestClientResponseException failure) {
            if (failure.getStatusCode().value() == 403) throw new AccessDeniedException("Access is denied");
            throw new AuthorizationUnavailableException();
        } catch (RuntimeException unavailable) {
            throw new AuthorizationUnavailableException();
        }
        if (access == null || !issuer.equals(access.issuer()) || !subject.equals(access.subject())
                || access.merchantUuid() == null || access.merchantId() == null || access.merchantId().length() > 100
                || !access.merchantId().matches("MER-[A-Z0-9]+(-[A-Z0-9]+)*")
                || !Set.of("MERCHANT_ADMIN", "MERCHANT_OPERATOR").contains(access.role() == null ? "" : access.role())
                || !Set.of("ACTIVE", "SUSPENDED").contains(access.status() == null ? "" : access.status()))
            throw new AuthorizationUnavailableException();
        return access;
    }

    private record Principal(String issuer, String subject) {}
    public record Access(String issuer, String subject, UUID merchantUuid, String merchantId, String role, String status) {}
}
