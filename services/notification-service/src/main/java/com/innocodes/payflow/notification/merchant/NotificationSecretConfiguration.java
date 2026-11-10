package com.innocodes.payflow.notification.merchant;

import com.innocodes.payflow.webhook.*;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

import java.nio.file.Path;

@Configuration
public class NotificationSecretConfiguration {
    @Bean
    SecretReader notificationSecretReader(
            @Value("${payflow.secrets.adapter:unavailable}") String adapter,
            @Value("${payflow.secrets.directory:}") String directory,
            @Value("${payflow.secrets.master-key-file:}") String key,
            Environment env) {
        if (adapter.equals("local-files")) {
            if (!env.acceptsProfiles(Profiles.of("dev")))
                throw new IllegalArgumentException("Local secrets require dev profile");
            return LocalEncryptedFileSecrets.reader(Path.of(directory), Path.of(key));
        }
        if (!adapter.equals("unavailable"))
            throw new IllegalArgumentException("Unknown secret adapter");
        return (merchant, reference, version) -> {
            throw new SecretUnavailableException();
        };
    }
}
