package com.innocodes.payflow.merchant.webhook;

import com.innocodes.payflow.webhook.*;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

import java.nio.file.Path;

@Configuration
public class SecretProviderConfiguration {
    @Bean
    SecretWriter merchantSecretWriter(
            @Value("${payflow.secrets.adapter:unavailable}") String adapter,
            @Value("${payflow.secrets.directory:}") String directory,
            @Value("${payflow.secrets.master-key-file:}") String key,
            Environment env) {
        if (adapter.equals("local-files")) {
            if (!env.acceptsProfiles(Profiles.of("dev")))
                throw new IllegalArgumentException("Local secrets require dev profile");
            return LocalEncryptedFileSecrets.writer(Path.of(directory), Path.of(key));
        }
        if (!adapter.equals("unavailable"))
            throw new IllegalArgumentException("Unknown secret adapter");
        return (merchant, version) -> {
            throw new SecretUnavailableException();
        };
    }

    @Bean
    SecretReader merchantSecretReader(
            @Value("${payflow.secrets.adapter:unavailable}") String adapter,
            @Value("${payflow.secrets.directory:}") String directory,
            @Value("${payflow.secrets.master-key-file:}") String key,
            Environment env) {
        if (adapter.equals("local-files") && !env.acceptsProfiles(Profiles.of("dev")))
            throw new IllegalArgumentException("Local secrets require dev profile");
        return adapter.equals("local-files")
                ? LocalEncryptedFileSecrets.reader(Path.of(directory), Path.of(key))
                : (merchant, reference, version) -> {
                    throw new SecretUnavailableException();
                };
    }
}
