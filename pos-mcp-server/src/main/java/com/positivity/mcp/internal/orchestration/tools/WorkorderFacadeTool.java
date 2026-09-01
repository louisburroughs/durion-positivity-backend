package com.positivity.mcp.internal.orchestration.tools;

import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class WorkorderFacadeTool {

    private final RestClient restClient;
    private final String workorderUriTemplate;
    private final String workorderSearchUriTemplate;
    private final String workorderStatusUriTemplate;
    private final String technicianLaborUriTemplate;

    public WorkorderFacadeTool(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder restClientBuilder,
            @Value("${pos.workorder.base-url}") @NonNull String baseUrl,
            @Value("${pos.workorder.workorder-uri-template}") @NonNull String workorderUriTemplate,
            @Value("${pos.workorder.search-uri-template}") @NonNull String workorderSearchUriTemplate,
            @Value("${pos.workorder.status-uri-template}") @NonNull String workorderStatusUriTemplate,
            @Value("${pos.workorder.technician-labor-uri-template}") @NonNull String technicianLaborUriTemplate) {
        this.restClient = ToolRestClientSupport.instrumentedClient(restClientBuilder, baseUrl);
        this.workorderUriTemplate = workorderUriTemplate;
        this.workorderSearchUriTemplate = workorderSearchUriTemplate;
        this.workorderStatusUriTemplate = workorderStatusUriTemplate;
        this.technicianLaborUriTemplate = technicianLaborUriTemplate;
    }

    @Tool(description = "Get full workorder details by workorder ID")
    public String getWorkorder(@ToolParam(description = "The workorder ID") @NonNull String workorderId) {
        return restClient
                .get()
                .uri(workorderUriTemplate, Map.of("workorderId", workorderId))
                .retrieve()
                .body(String.class);
    }

    @Tool(
            description = "Search workorders by a free-text query matched against customer name or a literal "
                    + "workorder id (a query that parses as a UUID is treated as a workorder id), optionally "
                    + "narrowed by an exact customerId, an exact vehicleId, an exact status, a createdAt date "
                    + "window (createdFrom/createdTo), and/or a technicianId — each combinable with the query "
                    + "and with each other. status must be an exact WorkorderStatus value (DRAFT, APPROVED, "
                    + "ASSIGNED, WORK_IN_PROGRESS, AWAITING_PARTS, AWAITING_APPROVAL, READY_FOR_PICKUP, "
                    + "COMPLETED, CANCELLED); an unrecognized value is rejected by the backend with 400. There "
                    + "is no \"open\" alias for status — an open-work-orders query loops this call once per "
                    + "open status (APPROVED, ASSIGNED, WORK_IN_PROGRESS, AWAITING_PARTS, AWAITING_APPROVAL, "
                    + "READY_FOR_PICKUP), each call still fully server-side filtered. createdFrom/createdTo "
                    + "(YYYY-MM-DD, inclusive on both ends) bound the workorder's createdAt timestamp in UTC. "
                    + "technicianId matches any technician who has logged a labor entry on the workorder, not "
                    + "the workorder's currently assigned technician — a workorder assigned to one technician "
                    + "but worked by another surfaces under the working technician's id. Row shape: workorderId, "
                    + "workorderNumber, estimateNumber, status, customerId, customerName, vehicleId, "
                    + "vehicleLabel, vin, createdAt. Returns only the first page of matches (default size 25, "
                    + "hard-capped at 100 — a larger request is silently clamped, visible in the response's own "
                    + "size/totalElements).")
    public String searchWorkorders(
            @ToolParam(description = "Free-text query matching customer name or a literal workorder id") @NonNull
                    String query,
            @ToolParam(
                            description = "Optional exact customer id (UUID) filter, combinable with the query",
                            required = false)
                    String customerId,
            @ToolParam(
                            description = "Optional exact vehicle id (UUID) filter, combinable with the query",
                            required = false)
                    String vehicleId,
            @ToolParam(
                            description = "Optional exact status filter (DRAFT, APPROVED, ASSIGNED, "
                                    + "WORK_IN_PROGRESS, AWAITING_PARTS, AWAITING_APPROVAL, READY_FOR_PICKUP, "
                                    + "COMPLETED, CANCELLED); no \"open\" alias — loop once per open status",
                            required = false)
                    String status,
            @ToolParam(description = "Optional createdAt window start, inclusive (YYYY-MM-DD, UTC)", required = false)
                    String createdFrom,
            @ToolParam(description = "Optional createdAt window end, inclusive (YYYY-MM-DD, UTC)", required = false)
                    String createdTo,
            @ToolParam(
                            description = "Optional technician id (UUID) who logged a labor entry on the "
                                    + "workorder — not the assigned technician",
                            required = false)
                    String technicianId) {
        StringBuilder template = new StringBuilder(workorderSearchUriTemplate);
        Map<String, String> uriParams = new HashMap<>();
        uriParams.put("query", query);
        appendQueryParam(template, uriParams, "customerId", customerId);
        appendQueryParam(template, uriParams, "vehicleId", vehicleId);
        appendQueryParam(template, uriParams, "status", status);
        appendQueryParam(template, uriParams, "createdFrom", createdFrom);
        appendQueryParam(template, uriParams, "createdTo", createdTo);
        appendQueryParam(template, uriParams, "technicianId", technicianId);
        return restClient.get().uri(template.toString(), uriParams).retrieve().body(String.class);
    }

    private static void appendQueryParam(
            StringBuilder template, Map<String, String> uriParams, String name, String value) {
        if (value != null && !value.isBlank()) {
            // The default template carries ?q={query}, but the property is env-overridable — a
            // path-only override must not get the filter glued onto its last path segment.
            template.append(template.indexOf("?") >= 0 ? '&' : '?')
                    .append(name)
                    .append("={")
                    .append(name)
                    .append('}');
            uriParams.put(name, value);
        }
    }

    @Tool(
            description = "Get the current lifecycle status of a workorder by workorder id (UUID). Fetches the "
                    + "workorder and returns just its identity and status fields; use getWorkorder for the full "
                    + "record.")
    public String getWorkorderStatus(@ToolParam(description = "The workorder id (UUID)") @NonNull String workorderId) {
        String body = restClient
                .get()
                .uri(workorderStatusUriTemplate, Map.of("workorderId", workorderId))
                .retrieve()
                .body(String.class);
        return body == null ? null : FacadeJsonSupport.workorderStatusProjection(body);
    }

    @Tool(
            description = "Get per-technician labor and revenue summary for a reporting period (Wave 2 E5). "
                    + "period must be a calendar month in YYYY-MM form (e.g. 2026-05) or a calendar year in "
                    + "YYYY form (e.g. 2026) — resolve a relative phrase like \"last month\" yourself before "
                    + "calling; do not loop this tool across more than a handful of periods for a "
                    + "multi-period trend. Returns rows — technicianId, name (null when the person replica "
                    + "has no record), completedWoCount, billedHours, laborRevenue — ordered by billedHours "
                    + "descending and capped at 100 technicians; the response's truncated flag is true when "
                    + "more technicians had activity in the window than that cap allowed. The three columns "
                    + "are independently windowed and can disagree at month boundaries: completedWoCount and "
                    + "laborRevenue are attributed to the technician on the completing state transition and "
                    + "anchor on the workorder's completion date, while billedHours anchors on the labor "
                    + "entry's own log time — a technician can show billedHours with completedWoCount=0 on "
                    + "work not yet completed, or the reverse. laborRevenue sums invoice labor totals for "
                    + "that technician's completed work orders, excluding (never zeroing) an invoice whose "
                    + "labor total is unknown. This tool does NOT accept a technicianId filter, a limit "
                    + "override, or a groupBy — it always ranks every technician for one window.")
    public String getTechnicianLaborAnalytics(
            @ToolParam(description = "Reporting period: YYYY-MM or YYYY") @NonNull String period) {
        ReportingPeriods.DateRange range = ReportingPeriods.toDateRange(period);
        return restClient
                .get()
                .uri(technicianLaborUriTemplate, Map.of("startDate", range.startDate(), "endDate", range.endDate()))
                .retrieve()
                .body(String.class);
    }
}
