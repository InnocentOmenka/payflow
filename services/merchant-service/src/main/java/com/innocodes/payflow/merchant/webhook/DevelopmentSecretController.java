package com.innocodes.payflow.merchant.webhook;

import com.innocodes.payflow.webhook.ProvisionedSecret;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@RestController
@Profile("dev")
@ConditionalOnProperty(name = "payflow.secrets.provisioning-enabled", havingValue = "true")
@RequestMapping("/api/v1/merchants/{id}/webhook/secrets")
public class DevelopmentSecretController {
    private final WebhookSecretService service;

    public DevelopmentSecretController(WebhookSecretService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<ProvisionedSecret> provision(
            @PathVariable UUID id, HttpServletRequest request) throws Exception {
        if (!java.net.InetAddress.getByName(request.getRemoteAddr()).isLoopbackAddress())
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Development provisioning requires a loopback client");
        return ResponseEntity.status(201)
                .cacheControl(CacheControl.noStore())
                .header("Pragma", "no-cache")
                .body(service.provision(id));
    }
}
