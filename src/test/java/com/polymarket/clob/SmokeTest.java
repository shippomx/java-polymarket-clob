package com.polymarket.clob;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SmokeTest {
    @Test
    void javaVersionIs17() {
        assertThat(Runtime.version().feature()).isEqualTo(17);
    }
}
