package com.positivity.mcp.internal.orchestration.tools;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Facade over shop operations. Shops ARE locations (a shopId is a locationId), and
 * pos-shop-manager publishes no shop status or queue view — so the status/queue tools compose the
 * location record, the pos-shop-manager schedule board, and the pos-workorder WIP board (#1519
 * WS-3.SHOPSTATUS / WS-3.SHOPQUEUE).
 */
@Component
public class ShopManagerFacadeTool {

    private final RestClient restClient;
    private final Clock clock;
    private final String locationUriTemplate;
    private final String scheduleUriTemplate;
    private final String wipUriTemplate;
    private final String shopSearchUriTemplate;

    public ShopManagerFacadeTool(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder restClientBuilder,
            @NonNull Clock clock,
            @Value("${pos.shopmanager.base-url}") @NonNull String baseUrl,
            @Value("${pos.shopmanager.location-uri-template}") @NonNull String locationUriTemplate,
            @Value("${pos.shopmanager.schedule-uri-template}") @NonNull String scheduleUriTemplate,
            @Value("${pos.shopmanager.wip-uri-template}") @NonNull String wipUriTemplate,
            @Value("${pos.shopmanager.search-uri-template}") @NonNull String shopSearchUriTemplate) {
        this.restClient = ToolRestClientSupport.instrumentedClient(restClientBuilder, baseUrl);
        this.clock = clock;
        this.locationUriTemplate = locationUriTemplate;
        this.scheduleUriTemplate = scheduleUriTemplate;
        this.wipUriTemplate = wipUriTemplate;
        this.shopSearchUriTemplate = shopSearchUriTemplate;
    }

    @Tool(
            description = "Get a composed status picture for a shop by its location id (UUID) — the shopId "
                    + "argument IS a locationId. Returns a JSON envelope with three sections: location (the "
                    + "shop's location record: name, code, address, active flag), schedule (today's schedule "
                    + "board with appointments grouped into resource lanes), and openWorkorders (the shop's "
                    + "work-in-progress workorders, first page only, default size 25). The location section "
                    + "is required — the status is degraded without it; a failed schedule or workorder "
                    + "section degrades only itself.")
    public String getShopStatus(@ToolParam(description = "The shop's location id (UUID)") @NonNull String shopId) {
        String today = LocalDate.now(clock).toString();
        return ToolComposition.named("shopStatus")
                .call(
                        "location",
                        () -> restClient
                                .get()
                                .uri(locationUriTemplate, Map.of("locationId", shopId))
                                .retrieve()
                                .body(String.class))
                .require("location")
                .call(
                        "schedule",
                        () -> restClient
                                .get()
                                .uri(scheduleUriTemplate, Map.of("locationId", shopId, "date", today))
                                .retrieve()
                                .body(String.class))
                .call(
                        "openWorkorders",
                        () -> restClient
                                .get()
                                .uri(wipUriTemplate, Map.of("locationId", shopId))
                                .retrieve()
                                .body(String.class))
                .render();
    }

    @Tool(
            description = "Get the active work queue for a shop by its location id (UUID) — the shopId "
                    + "argument IS a locationId. Returns a JSON envelope with two sections: openWorkorders "
                    + "(the shop's work-in-progress workorders: approved, assigned, in progress, awaiting "
                    + "parts or approval — first page only, default size 25) and schedule (today's schedule "
                    + "board for context). The openWorkorders section is required — the status is degraded "
                    + "without it.")
    public String getShopQueue(@ToolParam(description = "The shop's location id (UUID)") @NonNull String shopId) {
        String today = LocalDate.now(clock).toString();
        return ToolComposition.named("shopQueue")
                .call(
                        "openWorkorders",
                        () -> restClient
                                .get()
                                .uri(wipUriTemplate, Map.of("locationId", shopId))
                                .retrieve()
                                .body(String.class))
                .require("openWorkorders")
                .call(
                        "schedule",
                        () -> restClient
                                .get()
                                .uri(scheduleUriTemplate, Map.of("locationId", shopId, "date", today))
                                .retrieve()
                                .body(String.class))
                .render();
    }

    @Tool(
            description = "Search shops by name or code. Shops are locations: this lists the location roster "
                    + "and applies a case-insensitive contains-filter on the name and code fields; region, "
                    + "status, or manager are not searchable.")
    public String searchShops(@ToolParam(description = "Shop name or code (or part of it)") @NonNull String query) {
        String locations =
                restClient.get().uri(shopSearchUriTemplate).retrieve().body(String.class);
        return locations == null ? null : FacadeJsonSupport.filterLocationsByNameOrCode(locations, query);
    }
}
