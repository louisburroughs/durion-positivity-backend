package com.positivity.inboundmail.tenancy;

import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_A;
import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_B;
import static com.positivity.tenancy.testing.TenantTestSupport.asTenant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.inboundmail.internal.dto.IssuedAddressResponse;
import com.positivity.inboundmail.internal.dto.StagedAttachmentRef;
import com.positivity.inboundmail.internal.entity.InboundMessage;
import com.positivity.inboundmail.internal.enums.MessageStatus;
import com.positivity.inboundmail.internal.repository.InboundMessageRepository;
import com.positivity.inboundmail.internal.service.AddressRegistry;
import com.positivity.inboundmail.internal.service.AttachmentStaging;
import com.positivity.tenancy.TenantContext;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The edge API through the whole runtime (tenant filter, secret chain, RLS, the real database): the
 * three 401 codes, the address lifecycle over HTTP, and the staged-attachment 404 that never says
 * whose a ref is.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@DisplayName("Edge API on Postgres (CAP:550 S27)")
class InboundMailApiIT extends PostgresTenancyTestBase {

    private static final String SECRET = "pg-test-secret";
    private static final Instant NOW = Instant.parse("2026-10-17T12:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AddressRegistry registry;

    @Autowired
    private AttachmentStaging staging;

    @Autowired
    private InboundMessageRepository messages;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void aRequestWithoutATenantIsTenantRequired() throws Exception {
        mockMvc.perform(post("/inbound-mail/v1/addresses").header("X-Inbound-Mail-Secret", SECRET))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TENANT_REQUIRED"));
    }

    @Test
    void aWrongSecretIsRefusedEvenWithATenant() throws Exception {
        mockMvc.perform(post("/inbound-mail/v1/addresses")
                        .header("X-Inbound-Mail-Secret", "wrong")
                        .header("X-Tenant-Id", TENANT_A))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_INBOUND_MAIL_SECRET"));
        mockMvc.perform(post("/inbound-mail/v1/addresses").header("X-Tenant-Id", TENANT_A))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_INBOUND_MAIL_SECRET"));
    }

    @Test
    void theAddressLifecycleOverHttp() throws Exception {
        String first = exchange(TENANT_A, "/inbound-mail/v1/addresses", null);
        String again = exchange(TENANT_A, "/inbound-mail/v1/addresses", null);
        assertThat(again).as("issue is idempotent").isEqualTo(first);

        UUID requestId = UUID.randomUUID();
        String body = "{\"requestId\":\"" + requestId + "\"}";
        String rotated = exchange(TENANT_A, "/inbound-mail/v1/addresses/rotation", body);
        String replay = exchange(TENANT_A, "/inbound-mail/v1/addresses/rotation", body);

        assertThat(rotated).isNotEqualTo(first);
        assertThat(replay).as("rotation replay").isEqualTo(rotated);
        assertThat(exchange(TENANT_A, "/inbound-mail/v1/addresses", null)).isEqualTo(rotated);
    }

    @Test
    void aStagedAttachmentIsServedToItsTenantAndIsNotFoundForEveryoneElse() throws Exception {
        IssuedAddressResponse address = asTenant(TENANT_A, registry::issueOrGet);
        UUID messageId = asTenant(
                TENANT_A,
                () -> messages.saveAndFlush(InboundMessage.builder()
                                .addressId(address.addressId())
                                .messageKey("api-it-" + UUID.randomUUID())
                                .receivedAt(NOW)
                                .status(MessageStatus.STAGED)
                                .build())
                        .getInboundMessageId());
        StagedAttachmentRef ref = asTenant(
                TENANT_A,
                () -> staging.stage(
                        messageId, "invoice.pdf", "application/pdf", "hello".getBytes(StandardCharsets.UTF_8)));

        mockMvc.perform(get("/inbound-mail/v1/staged-attachments/{ref}", ref.ref())
                        .header("X-Inbound-Mail-Secret", SECRET)
                        .header("X-Tenant-Id", TENANT_A))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Content-SHA256", ref.sha256()))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().bytes("hello".getBytes(StandardCharsets.UTF_8)));

        MvcResult foreign = mockMvc.perform(get("/inbound-mail/v1/staged-attachments/{ref}", ref.ref())
                        .header("X-Inbound-Mail-Secret", SECRET)
                        .header("X-Tenant-Id", TENANT_B))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("STAGED_ATTACHMENT_NOT_FOUND"))
                .andReturn();
        MvcResult unknown = mockMvc.perform(get("/inbound-mail/v1/staged-attachments/{ref}", UUID.randomUUID())
                        .header("X-Inbound-Mail-Secret", SECRET)
                        .header("X-Tenant-Id", TENANT_A))
                .andExpect(status().isNotFound())
                .andReturn();
        assertThat(normalise(foreign)).as("a foreign ref and an unknown ref are indistinguishable").isEqualTo(normalise(unknown));
    }

    private String exchange(UUID tenant, String path, String body) throws Exception {
        var request = post(path).header("X-Inbound-Mail-Secret", SECRET).header("X-Tenant-Id", tenant);
        if (body != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mockMvc.perform(request)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.address").exists())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private static String normalise(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString().replaceAll("\"(timestamp|correlationId)\":\"[^\"]*\"", "");
    }
}
