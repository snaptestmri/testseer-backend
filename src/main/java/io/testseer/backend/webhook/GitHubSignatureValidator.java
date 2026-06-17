package io.testseer.backend.webhook;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

@Component
public class GitHubSignatureValidator {

    private final byte[] secretBytes;

    @Autowired
    public GitHubSignatureValidator(
            @Value("${testseer.github.webhook-secret:changeme}") String secret) {
        this.secretBytes = secret.getBytes(StandardCharsets.UTF_8);
    }

    // Private constructor - actual implementation
    private GitHubSignatureValidator(String secret, boolean unused) {
        this.secretBytes = secret.getBytes(StandardCharsets.UTF_8);
    }

    // Static factory for tests
    public static GitHubSignatureValidator forTesting(String secret) {
        return new GitHubSignatureValidator(secret, true);
    }

    public boolean isValid(String payload, String signatureHeader) {
        if (signatureHeader == null || !signatureHeader.startsWith("sha256=")) return false;
        String expected = computeSignature(payload);
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                signatureHeader.getBytes(StandardCharsets.UTF_8)
        );
    }

    String computeSignature(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secretBytes, "HmacSHA256"));
            byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return "sha256=" + bytesToHex(hash);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append("%02x".formatted(b));
        return sb.toString();
    }
}
