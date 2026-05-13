package com.positivity.inventory.contract;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class BaseContractIntegrationTest {

    protected MockHttpServletRequestBuilder withGatewayAuth(MockHttpServletRequestBuilder requestBuilder) {
        return requestBuilder
                .header("X-User", "contract-test-user")
                .header(
                        "X-Authorities",
                        String.join(
                                ",",
                                "inventory:on_hand:view",
                                "inventory:on_hand:search",
                                "inventory:adjustment:create",
                                "inventory:adjustment:approve",
                                "inventory:adjustment:view",
                                "inventory:stock_movement:create",
                                "inventory:location:view",
                                "inventory:location:admin",
                                "inventory:pick_list:create",
                                "inventory:pick_list:view",
                                "inventory:pick_list:execute",
                                "inventory:putaway:generate",
                                "inventory:putaway:view",
                                "inventory:putaway:claim",
                                "inventory:putaway:execute",
                                "inventory:cycle_count:initiate",
                                "inventory:cycle_count:view",
                                "inventory:cycle_count:complete",
                                "inventory:receiving:create",
                                "inventory:receiving:view",
                                "inventory:receiving:complete",
                                "inventory:issue:parts",
                                "inventory:override:part-match",
                                "inventory:purchase_order:create",
                                "inventory:purchase_order:view",
                                "inventory:purchase_order:approve",
                                "inventory:purchase_order:receive",
                                "inventory:asn:create",
                                "inventory:asn:view",
                                "inventory:goods_receipt:create",
                                "inventory:goods_receipt:view",
                                "inventory:goods_receipt:override",
                                "inventory:ledger:view",
                                "inventory:allocations:reallocate",
                                "inventory:shortage:resolve",
                                "inventory:return:write",
                                "inventory:return:view"));
    }

    protected MockHttpServletRequestBuilder withApproveOnlyAuth(MockHttpServletRequestBuilder requestBuilder) {
        return requestBuilder
                .header("X-User", "contract-test-user")
                .header("X-Authorities", "inventory:adjustment:approve");
    }

    protected MockHttpServletRequestBuilder withCreateOnlyAuth(MockHttpServletRequestBuilder requestBuilder) {
        return requestBuilder
                .header("X-User", "contract-test-user")
                .header("X-Authorities", "inventory:adjustment:create");
    }

    protected MockHttpServletRequestBuilder withReceivingAuth(MockHttpServletRequestBuilder requestBuilder) {
        return requestBuilder
                .header("X-User", "contract-test-user")
                .header(
                        "X-Authorities",
                        String.join(
                                ",",
                                "inventory:receiving:create",
                                "inventory:receiving:complete",
                                "inventory:receiving:view",
                                "inventory:issue:parts",
                                "inventory:override:part-match"));
    }
}
