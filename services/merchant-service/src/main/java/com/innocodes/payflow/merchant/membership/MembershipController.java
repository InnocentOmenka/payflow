package com.innocodes.payflow.merchant.membership;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/v1/merchants/{merchantId}/memberships")
public class MembershipController {
    private final MembershipAdministrationService service;
    public MembershipController(MembershipAdministrationService service) { this.service = service; }

    @PostMapping
    public ResponseEntity<MembershipResponse> create(@PathVariable UUID merchantId,
            @Valid @RequestBody CreateMembershipRequest request) {
        var result = service.create(merchantId, request);
        return ResponseEntity.created(ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}")
                .buildAndExpand(result.id()).toUri()).body(result);
    }

    @GetMapping
    public MembershipPageResponse list(@PathVariable UUID merchantId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.list(merchantId, page, size);
    }

    @PutMapping("/{membershipId}")
    public MembershipResponse updateRole(@PathVariable UUID merchantId, @PathVariable UUID membershipId,
            @Valid @RequestBody UpdateMembershipRequest request) {
        return service.updateRole(merchantId, membershipId, request);
    }

    @PostMapping("/{membershipId}/revoke")
    public MembershipResponse revoke(@PathVariable UUID merchantId, @PathVariable UUID membershipId,
            @Valid @RequestBody RevokeMembershipRequest request) {
        return service.revoke(merchantId, membershipId, request);
    }
}
