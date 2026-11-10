package com.innocodes.payflow.merchant.service;

import com.innocodes.payflow.merchant.dto.request.RegisterMerchantRequest;
import com.innocodes.payflow.merchant.dto.request.UpdateMerchantProfileRequest;
import com.innocodes.payflow.merchant.dto.response.MerchantPageResponse;
import com.innocodes.payflow.merchant.dto.response.MerchantResponse;
import com.innocodes.payflow.merchant.entity.Merchant;
import com.innocodes.payflow.merchant.enums.MerchantStatus;
import com.innocodes.payflow.merchant.exception.MerchantConflictException;
import com.innocodes.payflow.merchant.exception.MerchantNotFoundException;
import com.innocodes.payflow.merchant.repository.MerchantRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

@Service
@Validated
public class MerchantService {
    private final MerchantRepository repository;
    private final com.innocodes.payflow.merchant.webhook.WebhookConfigurationService webhooks;

    public MerchantService(MerchantRepository repository, com.innocodes.payflow.merchant.webhook.WebhookConfigurationService webhooks) {
        this.webhooks=webhooks;
        this.repository = repository;
    }

    @Transactional
    public MerchantResponse register(@NotNull @Valid RegisterMerchantRequest request) {
        Merchant merchant = new Merchant(UUID.randomUUID(), request.merchantId(), request.businessName(),
                request.businessEmail(), request.contactPhone(), Instant.now().truncatedTo(ChronoUnit.MICROS));
        // Database uniqueness is authoritative, including concurrent registrations.
        // Do not catch a constraint violation and query inside the aborted transaction.
        return MerchantResponse.from(repository.saveAndFlush(merchant));
    }

    @Transactional(readOnly = true)
    public MerchantResponse getById(@NotNull UUID id) {
        return MerchantResponse.from(find(id));
    }

    @Transactional(readOnly = true)
    public MerchantResponse getByMerchantId(@NotBlank @Size(max = 100) String merchantId) {
        return MerchantResponse.from(repository.findByMerchantId(merchantId.strip().toUpperCase(Locale.ROOT))
                .orElseThrow(MerchantNotFoundException::new));
    }

    @Transactional
    public MerchantResponse updateProfile(@NotNull UUID id, @NotNull @Valid UpdateMerchantProfileRequest request) {
        Merchant merchant = find(id);
        requireVersion(merchant, request.version());
        merchant.updateProfile(request.businessName(), request.businessEmail(), request.contactPhone(), Instant.now().truncatedTo(ChronoUnit.MICROS));
        repository.flush();
        return MerchantResponse.from(merchant);
    }

    @Transactional
    public MerchantResponse activate(@NotNull UUID id, @NotNull @PositiveOrZero Long version) {
        return transition(id, version, MerchantStatus.ACTIVE);
    }

    @Transactional
    public MerchantResponse suspend(@NotNull UUID id, @NotNull @PositiveOrZero Long version) {
        return transition(id, version, MerchantStatus.SUSPENDED);
    }

    @Transactional
    public MerchantResponse close(@NotNull UUID id, @NotNull @PositiveOrZero Long version) {
        return transition(id, version, MerchantStatus.CLOSED);
    }

    @Transactional(readOnly = true)
    public MerchantPageResponse list(@Min(0) int page, @Min(1) @Max(100) int size,
                                     MerchantStatus status, @Size(max = 200) String search) {
        Specification<Merchant> filter = (root, query, cb) -> cb.conjunction();
        if (status != null) {
            filter = filter.and((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        if (search != null && !search.isBlank()) {
            String literal = search.strip().toLowerCase(Locale.ROOT)
                    .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
            filter = filter.and((root, query, cb) -> cb.like(cb.lower(root.get("businessName")), "%" + literal + "%", '\\'));
        }
        var result = repository.findAll(filter, PageRequest.of(page, size,
                Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by("id"))));
        return new MerchantPageResponse(result.getContent().stream().map(MerchantResponse::from).toList(),
                result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages(),
                result.isFirst(), result.isLast());
    }

    private MerchantResponse transition(UUID id, Long version, MerchantStatus target) {
        Merchant merchant = find(id);
        requireVersion(merchant, version);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        switch (target) {
            case ACTIVE -> merchant.activate(now);
            case SUSPENDED -> merchant.suspend(now);
            case CLOSED -> merchant.close(now);
            default -> throw new IllegalArgumentException("Unsupported lifecycle operation");
        }
        repository.flush();
        webhooks.merchantStatusChanged(merchant);
        return MerchantResponse.from(merchant);
    }

    private Merchant find(UUID id) {
        return repository.findById(id).orElseThrow(MerchantNotFoundException::new);
    }

    private void requireVersion(Merchant merchant, Long expected) {
        if (!Objects.equals(merchant.getVersion(), expected)) {
            throw new MerchantConflictException("Merchant version is stale; retrieve the latest profile before retrying");
        }
    }
}
