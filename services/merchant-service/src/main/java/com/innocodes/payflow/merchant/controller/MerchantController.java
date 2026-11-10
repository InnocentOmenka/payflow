package com.innocodes.payflow.merchant.controller;

import com.innocodes.payflow.merchant.dto.request.MerchantStatusRequest;
import com.innocodes.payflow.merchant.dto.request.RegisterMerchantRequest;
import com.innocodes.payflow.merchant.dto.request.UpdateMerchantProfileRequest;
import com.innocodes.payflow.merchant.dto.response.MerchantPageResponse;
import com.innocodes.payflow.merchant.dto.response.MerchantResponse;
import com.innocodes.payflow.merchant.enums.MerchantStatus;
import com.innocodes.payflow.merchant.service.MerchantService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/merchants")
public class MerchantController {
    private final MerchantService service;

    public MerchantController(MerchantService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<MerchantResponse> register(@Valid @RequestBody RegisterMerchantRequest request) {
        MerchantResponse response = service.register(request);
        return ResponseEntity.created(ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}")
                .buildAndExpand(response.id()).toUri()).body(response);
    }

    @GetMapping("/{id}")
    public MerchantResponse getById(@PathVariable UUID id) {
        return service.getById(id);
    }

    @GetMapping("/by-identifier/{merchantId}")
    public MerchantResponse getByMerchantId(@PathVariable @Size(max = 100) String merchantId) {
        return service.getByMerchantId(merchantId);
    }

    @PutMapping("/{id}/profile")
    public MerchantResponse updateProfile(@PathVariable UUID id, @Valid @RequestBody UpdateMerchantProfileRequest request) {
        return service.updateProfile(id, request);
    }

    @GetMapping
    public MerchantPageResponse list(@RequestParam(defaultValue = "0") @Min(0) int page,
                                     @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
                                     @RequestParam(required = false) MerchantStatus status,
                                     @RequestParam(required = false) @Size(max = 200) String search) {
        return service.list(page, size, status, search);
    }

    @PostMapping("/{id}/activate")
    public MerchantResponse activate(@PathVariable UUID id, @Valid @RequestBody MerchantStatusRequest request) {
        return service.activate(id, request.version());
    }

    @PostMapping("/{id}/suspend")
    public MerchantResponse suspend(@PathVariable UUID id, @Valid @RequestBody MerchantStatusRequest request) {
        return service.suspend(id, request.version());
    }

    @PostMapping("/{id}/close")
    public MerchantResponse close(@PathVariable UUID id, @Valid @RequestBody MerchantStatusRequest request) {
        return service.close(id, request.version());
    }
}
