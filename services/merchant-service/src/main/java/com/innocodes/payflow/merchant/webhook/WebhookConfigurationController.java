package com.innocodes.payflow.merchant.webhook;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/merchants/{id}/webhook")
public class WebhookConfigurationController {
    private final WebhookConfigurationService service;

    public WebhookConfigurationController(WebhookConfigurationService service) {
        this.service = service;
    }

    @PostMapping("/replay")
    public org.springframework.http.ResponseEntity<Void> replay(@PathVariable UUID id) {
        service.replay(id);
        return org.springframework.http.ResponseEntity.accepted().build();
    }

    @GetMapping
    public WebhookConfiguration get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PutMapping
    public WebhookConfiguration update(
            @PathVariable UUID id, @Valid @RequestBody UpdateWebhookRequest request) {
        return service.update(id, request);
    }
}
