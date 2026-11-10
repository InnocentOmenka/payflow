package com.innocodes.payflow.webhook;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;

class LocalEncryptedFileSecretsTest {
    @TempDir Path dir;

    private Path key() throws Exception {
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
        Path key = dir.resolve("master");
        byte[] bytes = new byte[32];
        new java.security.SecureRandom().nextBytes(bytes);
        Files.write(key, bytes);
        Files.setPosixFilePermissions(key, PosixFilePermissions.fromString("rw-------"));
        return key;
    }

    @Test
    void shouldEncryptBindAndResolveAcrossIndependentProviderInstances() throws Exception {
        var key = key();
        var root = dir.resolve("secrets");
        var value = LocalEncryptedFileSecrets.writer(root, key).provision("MER-1", 1);
        var reader = LocalEncryptedFileSecrets.reader(root, key);
        assertEquals(value.secret(), reader.resolve("MER-1", value.reference(), 1));
        assertThrows(
                SecretUnavailableException.class,
                () -> reader.resolve("MER-OTHER", value.reference(), 1));
        assertFalse(
                Files.readString(
                                root.resolve(value.reference() + "-1.enc"),
                                java.nio.charset.StandardCharsets.ISO_8859_1)
                        .contains(value.secret()));
        assertFalse(value.toString().contains(value.secret()));
        assertFalse(reader instanceof SecretWriter);
        var next = LocalEncryptedFileSecrets.writer(root, key).provision("MER-1", 2);
        assertNotEquals(value.secret(), next.secret());
        assertEquals(value.secret(), reader.resolve("MER-1", value.reference(), 1));
    }

    @Test
    void shouldFailClosedForTamperingMissingKeysAndUnsafePermissions() throws Exception {
        var key = key();
        var root = dir.resolve("secrets");
        var writer = LocalEncryptedFileSecrets.writer(root, key);
        var value = writer.provision("MER-1", 1);
        var reader = LocalEncryptedFileSecrets.reader(root, key);
        Files.write(root.resolve(value.reference() + "-1.enc"), new byte[40]);
        assertThrows(
                SecretUnavailableException.class,
                () -> reader.resolve("MER-1", value.reference(), 1));
        assertThrows(
                SecretUnavailableException.class,
                () -> reader.resolve("MER-1", java.util.UUID.randomUUID(), 1));
        Files.setPosixFilePermissions(key, PosixFilePermissions.fromString("rw-r--r--"));
        assertThrows(
                SecretUnavailableException.class,
                () -> LocalEncryptedFileSecrets.reader(root, key));
    }
}
