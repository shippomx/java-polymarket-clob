package com.polymarket.clob.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.polymarket.clob.http.JsonCodec;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApiCredentialsTest {

    private final ObjectMapper mapper = JsonCodec.objectMapper();

    @Test
    void deserializesAllFieldsFromServerResponse() {
        String body = """
                {"apiKey":"00000000-0000-0000-0000-000000000000",
                 "secret":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
                 "passphrase":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}
                """;
        ApiCredentials c = JsonCodec.readValue(mapper, body, ApiCredentials.class);

        assertThat(c.apiKey()).isEqualTo("00000000-0000-0000-0000-000000000000");
        assertThat(c.secret()).isEqualTo("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        assertThat(c.passphrase()).hasSize(64);
    }

    @Test
    void serializingDoesNotLeakSecretOrPassphrase() {
        ApiCredentials c = new ApiCredentials(
                "00000000-0000-0000-0000-000000000000",
                "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        String json = JsonCodec.writeValue(mapper, c);
        assertThat(json).contains("00000000-0000-0000-0000-000000000000")
                .doesNotContain("AAAAAAAAAAAA")
                .doesNotContainIgnoringCase("passphrase");
    }

    @Test
    void toStringMasksSensitiveFields() {
        ApiCredentials c = new ApiCredentials(
                "00000000-0000-0000-0000-000000000000",
                "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        String s = c.toString();
        assertThat(s)
                .contains("00000000-0000-0000-0000-000000000000")
                .doesNotContainIgnoringCase("AAAAAAAA")
                .doesNotContainIgnoringCase("aaaaaaaa");
    }

    @Test
    void equalsAndHashCode() {
        ApiCredentials a = new ApiCredentials("k", "s", "p");
        ApiCredentials b = new ApiCredentials("k", "s", "p");
        ApiCredentials c = new ApiCredentials("k2", "s", "p");
        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(c);
    }
}
