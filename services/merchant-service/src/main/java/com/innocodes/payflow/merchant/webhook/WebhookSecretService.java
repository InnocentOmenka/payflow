package com.innocodes.payflow.merchant.webhook;

import com.innocodes.payflow.merchant.enums.MerchantStatus;
import com.innocodes.payflow.merchant.exception.*;
import com.innocodes.payflow.merchant.repository.MerchantRepository;
import com.innocodes.payflow.webhook.*;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class WebhookSecretService {
    private final MerchantRepository merchants;
    private final JdbcTemplate jdbc;
    private final SecretWriter writer;

    public WebhookSecretService(
            MerchantRepository merchants, JdbcTemplate jdbc, SecretWriter writer) {
        this.merchants = merchants;
        this.jdbc = jdbc;
        this.writer = writer;
    }

    @Transactional
    public ProvisionedSecret provision(UUID id) {
        var merchant = merchants.findByIdForUpdate(id).orElseThrow(MerchantNotFoundException::new);
        if (merchant.getStatus() == MerchantStatus.CLOSED
                || merchant.getStatus() == MerchantStatus.SUSPENDED)
            throw new MerchantConflictException(
                    "Suspended or closed merchants cannot provision signing keys");
        long version =
                jdbc.queryForObject(
                        "SELECT coalesce(max(version),0)+1 FROM merchant_webhook_secrets WHERE"
                            + " merchant_uuid=?",
                        Long.class,
                        id);
        var secret = writer.provision(merchant.getMerchantId(), version);
        jdbc.update(
                "INSERT INTO merchant_webhook_secrets(reference,merchant_uuid,version)"
                    + " VALUES(?,?,?)",
                secret.reference(),
                id,
                version);
        return secret;
    }
}
