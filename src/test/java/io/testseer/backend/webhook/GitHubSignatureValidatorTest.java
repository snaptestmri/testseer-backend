package io.testseer.backend.webhook;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GitHubSignatureValidatorTest {

    private final GitHubSignatureValidator validator =
            new GitHubSignatureValidator("test-secret");

    @Test
    void validSignature_returnsTrue() {
        String payload = "{\"action\":\"opened\"}";
        String sig = validator.computeSignature(payload);
        assertThat(validator.isValid(payload, sig)).isTrue();
    }

    @Test
    void wrongSecret_returnsFalse() {
        String payload = "{\"action\":\"opened\"}";
        GitHubSignatureValidator other = new GitHubSignatureValidator("wrong-secret");
        String sig = other.computeSignature(payload);
        assertThat(validator.isValid(payload, sig)).isFalse();
    }

    @Test
    void nullSignature_returnsFalse() {
        assertThat(validator.isValid("{\"action\":\"opened\"}", null)).isFalse();
    }

    @Test
    void missingPrefix_returnsFalse() {
        String payload = "{\"action\":\"opened\"}";
        String sig = validator.computeSignature(payload).replace("sha256=", "");
        assertThat(validator.isValid(payload, sig)).isFalse();
    }
}
