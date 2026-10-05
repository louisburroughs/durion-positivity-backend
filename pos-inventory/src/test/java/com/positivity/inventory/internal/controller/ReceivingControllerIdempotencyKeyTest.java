package com.positivity.inventory.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** #2455: how a receive or cross-dock call resolves its idempotency key from header and body. */
class ReceivingControllerIdempotencyKeyTest {

    @Test
    void headerWinsOverAbsentBody_andIsTrimmed() {
        assertThat(ReceivingController.resolveIdempotencyKey("  k1 ", null)).isEqualTo("k1");
        assertThat(ReceivingController.resolveIdempotencyKey(null, "k2")).isEqualTo("k2");
        assertThat(ReceivingController.resolveIdempotencyKey(" ", null)).isNull();
    }

    @Test
    void differingHeaderAndBody_isRejected() {
        assertThatThrownBy(() -> ReceivingController.resolveIdempotencyKey("a", "b"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void keyOver200Characters_isRejectedFromEitherSource() {
        String tooLong = "k".repeat(201);
        assertThatThrownBy(() -> ReceivingController.resolveIdempotencyKey(tooLong, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ReceivingController.resolveIdempotencyKey(null, tooLong))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(ReceivingController.resolveIdempotencyKey("k".repeat(200), null))
                .hasSize(200);
    }
}
