package com.polymarket.clob.funxyz;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FunxyzExceptionTest {

    @Test
    void httpStatusIsExposed() {
        FunxyzException ex = new FunxyzException("boom", 401);
        assertThat(ex.httpStatus()).isEqualTo(401);
        assertThat(ex.getMessage()).isEqualTo("boom");
        assertThat(ex.getCause()).isNull();
    }

    @Test
    void causeIsExposed() {
        Throwable cause = new RuntimeException("io");
        FunxyzException ex = new FunxyzException("wrap", -1, cause);
        assertThat(ex.getCause()).isSameAs(cause);
        assertThat(ex.httpStatus()).isEqualTo(-1);
    }

    @Test
    void isTransportTrueWhenStatusNegative() {
        assertThat(new FunxyzException("io", -1).isTransport()).isTrue();
        assertThat(new FunxyzException("ok", 200).isTransport()).isFalse();
        assertThat(new FunxyzException("4xx", 401).isTransport()).isFalse();
    }

    @Test
    void isBlockedTrueOnlyForCanonicalMessage() {
        assertThat(new FunxyzException("eoa blocked by fun.xyz", 200).isBlocked()).isTrue();
        assertThat(new FunxyzException("something else", 200).isBlocked()).isFalse();
        assertThat(new FunxyzException("eoa blocked by fun.xyz", -1).isBlocked()).isTrue();  // message-driven
    }
}
