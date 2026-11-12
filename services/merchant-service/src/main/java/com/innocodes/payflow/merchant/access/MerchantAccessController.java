package com.innocodes.payflow.merchant.access;

import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/v1/merchant-access/resolve")
public class MerchantAccessController {
    private final MerchantAccessService service;
    public MerchantAccessController(MerchantAccessService service) { this.service = service; }

    @PostMapping
    public ResponseEntity<MerchantAccessService.Access> resolve(@Valid @RequestBody MerchantAccessRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.resolve(request));
    }
}
