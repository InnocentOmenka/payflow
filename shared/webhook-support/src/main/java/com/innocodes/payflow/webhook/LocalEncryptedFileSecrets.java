package com.innocodes.payflow.webhook;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Development adapter only. Shared master-key access is not a production IAM boundary. */
public final class LocalEncryptedFileSecrets {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final Path root;
    private final byte[] key;

    private LocalEncryptedFileSecrets(Path root, Path keyFile, boolean writer) {
        try {
            this.root = root.toAbsolutePath().normalize();
            if (writer && !Files.exists(this.root))
                Files.createDirectories(
                        this.root,
                        PosixFilePermissions.asFileAttribute(
                                PosixFilePermissions.fromString("rwx------")));
            privatePath(this.root);
            privatePath(keyFile);
            try (var input = Files.newInputStream(keyFile, LinkOption.NOFOLLOW_LINKS)) {
                key = input.readNBytes(33);
            }
            if (key.length != 32)
                throw new IllegalArgumentException(
                        "Development master key must be exactly 32 bytes");
        } catch (Exception exception) {
            throw new SecretUnavailableException();
        }
    }

    public static SecretReader reader(Path root, Path keyFile) {
        var store = new LocalEncryptedFileSecrets(root, keyFile, false);
        return store::read;
    }

    public static SecretWriter writer(Path root, Path keyFile) {
        var store = new LocalEncryptedFileSecrets(root, keyFile, true);
        return store::write;
    }

    private ProvisionedSecret write(String merchant, long version) {
        UUID reference = UUID.randomUUID();
        try {
            if (version < 1) throw new IllegalArgumentException();
            byte[] bytes = new byte[32];
            RANDOM.nextBytes(bytes);
            String value = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            byte[] nonce = new byte[12];
            RANDOM.nextBytes(nonce);
            Cipher cipher = cipher(Cipher.ENCRYPT_MODE, merchant, reference, version, nonce);
            byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            Path file = file(reference, version);
            try (var channel =
                    FileChannel.open(
                            file,
                            Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                            PosixFilePermissions.asFileAttribute(
                                    PosixFilePermissions.fromString("rw-------")))) {
                ByteBuffer buffer =
                        ByteBuffer.allocate(1 + nonce.length + encrypted.length)
                                .put((byte) 1)
                                .put(nonce)
                                .put(encrypted)
                                .flip();
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            return new ProvisionedSecret(reference, version, value);
        } catch (Exception exception) {
            throw new SecretUnavailableException();
        }
    }

    private String read(String merchant, UUID reference, long version) {
        try {
            Path file = file(reference, version);
            privatePath(file);
            byte[] envelope;
            try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                envelope = input.readNBytes(256);
            }
            if (envelope.length < 30 || envelope.length >= 256 || envelope[0] != 1)
                throw new IllegalArgumentException();
            byte[] nonce = java.util.Arrays.copyOfRange(envelope, 1, 13);
            return new String(
                    cipher(Cipher.DECRYPT_MODE, merchant, reference, version, nonce)
                            .doFinal(envelope, 13, envelope.length - 13),
                    StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw new SecretUnavailableException();
        }
    }

    private Cipher cipher(int mode, String merchant, UUID reference, long version, byte[] nonce)
            throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        cipher.updateAAD(
                (merchant + "\n" + reference + "\n" + version).getBytes(StandardCharsets.UTF_8));
        return cipher;
    }

    private Path file(UUID reference, long version) {
        if (reference == null || version < 1) throw new IllegalArgumentException();
        return root.resolve(reference + "-" + version + ".enc");
    }

    private static void privatePath(Path path) throws Exception {
        if (Files.isSymbolicLink(path)) throw new IllegalArgumentException();
        var permissions = Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS);
        if (permissions.stream()
                .anyMatch(p -> p.name().startsWith("GROUP_") || p.name().startsWith("OTHERS_")))
            throw new IllegalArgumentException();
    }
}
