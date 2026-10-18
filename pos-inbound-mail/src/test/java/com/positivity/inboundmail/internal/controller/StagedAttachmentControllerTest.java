package com.positivity.inboundmail.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.positivity.inboundmail.internal.config.InboundMailProperties;
import com.positivity.inboundmail.internal.dto.StagedAttachmentBytes;
import com.positivity.inboundmail.internal.exception.StagingBusyException;
import com.positivity.inboundmail.internal.service.AttachmentStaging;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

class StagedAttachmentControllerTest {

    private final AttachmentStaging staging = mock(AttachmentStaging.class);

    private StagedAttachmentController controller(int slots) {
        return new StagedAttachmentController(
                staging,
                new InboundMailProperties(
                        "s", "none", "mail.example.com", Duration.ofDays(7), "x", 1L, 200, slots));
    }

    @Test
    void aFetchBeyondTheSlotCapIsBusyAndTheSlotIsFreedAfterwards() throws Exception {
        StagedAttachmentController controller = controller(1);
        UUID outer = UUID.randomUUID();
        UUID inner = UUID.randomUUID();
        // While the outer fetch holds the only slot, a second one arrives.
        when(staging.fetch(outer)).thenAnswer(invocation -> {
            assertThatThrownBy(() -> controller.get(inner, new MockHttpServletResponse()))
                    .isInstanceOf(StagingBusyException.class);
            return Optional.of(new StagedAttachmentBytes(new byte[] {1, 2, 3}, 3, "abc"));
        });
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.get(outer, response);

        assertThat(response.getContentAsByteArray()).containsExactly(1, 2, 3);
        assertThat(response.getHeader("X-Content-SHA256")).isEqualTo("abc");
        // The slot came back: the same controller serves again.
        when(staging.fetch(inner)).thenReturn(Optional.of(new StagedAttachmentBytes(new byte[] {9}, 1, "d")));
        MockHttpServletResponse again = new MockHttpServletResponse();
        controller.get(inner, again);
        assertThat(again.getContentAsByteArray()).containsExactly(9);
    }

    @Test
    void aMissingRefFreesItsSlotToo() {
        StagedAttachmentController controller = controller(1);
        UUID ref = UUID.randomUUID();
        when(staging.fetch(ref)).thenReturn(Optional.empty());

        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> controller.get(ref, new MockHttpServletResponse()))
                    .as("404, never busy: the slot is released on the failure path")
                    .isNotInstanceOf(StagingBusyException.class);
        }
    }
}
