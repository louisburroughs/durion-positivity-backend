package com.positivity.marketing.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.marketing.internal.dto.MessageTemplateResponse;
import com.positivity.marketing.internal.dto.UpsertMessageTemplateRequest;
import com.positivity.marketing.internal.enums.AudienceType;
import com.positivity.marketing.internal.enums.CampaignChannel;
import com.positivity.marketing.internal.security.MarketingPermissionRegistry;
import com.positivity.marketing.service.MessageTemplateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Message templates with audience-scoped token validation (Story #1147). */
@Tag(name = "Marketing Templates", description = "Channel-specific message templates with token substitution")
@RestController
@RequestMapping("/v1/marketing/templates")
public class MessageTemplateController {

    static final String TEMPLATE_EXAMPLE = """
            {"name":"Spring fleet email",
             "channel":"EMAIL",
             "audienceType":"COMMERCIAL",
             "subject":"{{accountName}}, your spring fleet alignment offer",
             "body":"Hi {{contactFirstName}}, {{accountName}} qualifies for our spring alignment offer. \
            Use code {{offerCode}} at {{shopName}}. Unsubscribe: {{unsubscribeUrl}}"}
            """;

    private final MessageTemplateService templateService;

    public MessageTemplateController(MessageTemplateService templateService) {
        this.templateService = templateService;
    }

    @Operation(operationId = "listMessageTemplates", summary = "List Message Templates", description = """
                    Lists message templates ordered by name, optionally filtered by delivery channel and \
                    audience type.
                    Use this tool when browsing templates or finding one to attach to a campaign; do not use \
                    getMessageTemplateById, which requires a known template id and returns exactly one \
                    template.
                    Preconditions: none; an empty result simply means no template matches the filters.
                    Required inputs: none; channel (EMAIL or SMS) and audienceType (COMMERCIAL or \
                    INDIVIDUAL) are optional query filters that combine when both are supplied.
                    Emits a MARKETING_TEMPLATE_LIST audit event; no marketing state is changed.
                    Returns 200 with an empty array when nothing matches, so an empty result is not an error \
                    condition.
                    """)
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Templates returned",
                content =
                        @Content(
                                mediaType = "application/json",
                                array =
                                        @ArraySchema(
                                                schema = @Schema(implementation = MessageTemplateResponse.class)))),
        @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions", content = @Content)
    })
    @GetMapping
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {MarketingPermissionRegistry.TEMPLATE_VIEW})
    @PreAuthorize("hasAuthority('marketing:template:view')")
    @EmitEvent(id = "MARKETING_TEMPLATE_LIST", apiVersion = "1")
    public ResponseEntity<List<MessageTemplateResponse>> list(
            @RequestParam(name = "channel", required = false) CampaignChannel channel,
            @RequestParam(name = "audienceType", required = false) AudienceType audienceType) {
        return ResponseEntity.ok(templateService.list(channel, audienceType));
    }

    @Operation(operationId = "getMessageTemplateById", summary = "Get Message Template By Id", description = """
                    Returns one message template with its name, channel, audience type, subject, body, and \
                    the substitution tokens available to its audience.
                    Use this tool when the template id is already known; use listMessageTemplates instead \
                    when searching by channel or audience.
                    Preconditions: the template must exist.
                    Required inputs: templateId (UUID) as a path parameter; there is no request body.
                    Emits a MARKETING_TEMPLATE_GET audit event; no marketing state is changed and this is a \
                    read-only projection.
                    Returns 404 when no template exists for the supplied id.
                    """)
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Template returned",
                content = @Content(schema = @Schema(implementation = MessageTemplateResponse.class))),
        @ApiResponse(responseCode = "404", description = "Template not found", content = @Content),
        @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions", content = @Content)
    })
    @GetMapping("/{templateId}")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {MarketingPermissionRegistry.TEMPLATE_VIEW})
    @PreAuthorize("hasAuthority('marketing:template:view')")
    @EmitEvent(id = "MARKETING_TEMPLATE_GET", apiVersion = "1")
    public ResponseEntity<MessageTemplateResponse> get(@PathVariable UUID templateId) {
        return ResponseEntity.ok(templateService.get(templateId));
    }

    @Operation(operationId = "createMessageTemplate", summary = "Create Message Template", description = """
                    Creates a channel-specific message template whose {{token}} placeholders are validated \
                    against the closed vocabulary for its audience at save time, so a typo costs a \
                    validation error rather than a batch of broken messages.
                    Use this tool when authoring a new template; do not use updateMessageTemplate, which \
                    revises an existing template and cannot change its channel or audienceType.
                    Preconditions: no template may already use the same name (compared case-insensitively); \
                    commercial-only tokens such as accountName are rejected for INDIVIDUAL audiences and \
                    vehicle tokens for COMMERCIAL ones.
                    Required inputs: name, channel (EMAIL or SMS), audienceType (COMMERCIAL or INDIVIDUAL) \
                    and body; subject is required for EMAIL, and for SMS it must be absent and any supplied \
                    value is discarded.
                    Emits a MARKETING_TEMPLATE_CREATE event; no campaign is affected until the template is \
                    attached to one.
                    Returns 409 when the name is already in use, and 422 when a token is unknown or outside \
                    the audience's vocabulary, an email template lacks a subject, an SMS template has one, \
                    or an SMS body exceeds 320 characters.
                    """)
    @ApiResponses({
        @ApiResponse(
                responseCode = "201",
                description = "Template created",
                content = @Content(schema = @Schema(implementation = MessageTemplateResponse.class))),
        @ApiResponse(responseCode = "409", description = "Template name already exists", content = @Content),
        @ApiResponse(
                responseCode = "422",
                description = "Invalid token, missing subject, or SMS body over the segment limit",
                content = @Content),
        @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions", content = @Content)
    })
    @PostMapping
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {MarketingPermissionRegistry.TEMPLATE_MANAGE})
    @PreAuthorize("hasAuthority('marketing:template:manage')")
    @EmitEvent(id = "MARKETING_TEMPLATE_CREATE", apiVersion = "1")
    public ResponseEntity<MessageTemplateResponse> create(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Template to create: name, channel, audience, subject (EMAIL only) and a"
                                    + " body whose {{token}} placeholders must belong to the audience's vocabulary.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(
                                                            name = "Commercial email template",
                                                            value = TEMPLATE_EXAMPLE)))
                    @Valid
                    @RequestBody
                    UpsertMessageTemplateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(templateService.create(request));
    }

    @Operation(operationId = "updateMessageTemplate", summary = "Update Message Template", description = """
                    Updates a template's name, subject and body, re-validating every {{token}} against the \
                    audience's vocabulary.
                    Use this tool to revise wording; do not use createMessageTemplate for a different \
                    channel or audience, because channel and audienceType are immutable and changing either \
                    requires a new template.
                    Preconditions: the template must exist, the channel and audienceType in the body must \
                    equal the stored values, and no other template may hold the new name.
                    Required inputs: templateId (UUID) path parameter plus the full body (name, channel, \
                    audienceType, body); subject is required for EMAIL and discarded for SMS.
                    Emits a MARKETING_TEMPLATE_UPDATE event; campaigns already bound to the template pick up \
                    the new wording at their next dispatch.
                    Returns 404 when the template does not exist, 409 when the name belongs to another \
                    template, and 422 when channel or audienceType is changed or the token, subject or SMS \
                    length rules fail.
                    """)
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Template updated",
                content = @Content(schema = @Schema(implementation = MessageTemplateResponse.class))),
        @ApiResponse(responseCode = "404", description = "Template not found", content = @Content),
        @ApiResponse(responseCode = "409", description = "Template name already exists", content = @Content),
        @ApiResponse(
                responseCode = "422",
                description = "Invalid token, or an immutable field was changed",
                content = @Content),
        @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions", content = @Content)
    })
    @PutMapping("/{templateId}")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {MarketingPermissionRegistry.TEMPLATE_MANAGE})
    @PreAuthorize("hasAuthority('marketing:template:manage')")
    @EmitEvent(id = "MARKETING_TEMPLATE_UPDATE", apiVersion = "1")
    public ResponseEntity<MessageTemplateResponse> update(
            @PathVariable UUID templateId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Full replacement template; channel and audienceType must match the stored"
                                    + " values because both are immutable.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(
                                                            name = "Commercial email template",
                                                            value = TEMPLATE_EXAMPLE)))
                    @Valid
                    @RequestBody
                    UpsertMessageTemplateRequest request) {
        return ResponseEntity.ok(templateService.update(templateId, request));
    }

    @Operation(operationId = "deleteMessageTemplate", summary = "Delete Unused Message Template", description = """
                    Permanently deletes a message template that no campaign references.
                    Use this tool to retire an unused template; detach it from every campaign first via \
                    updateCampaign rather than expecting the delete to cascade.
                    Preconditions: the template must exist and must not be referenced as the email or SMS \
                    template of any campaign, because a campaign whose template vanished would fail at \
                    dispatch across its whole audience.
                    Required inputs: templateId (UUID) as a path parameter; there is no request body and no \
                    confirmation flag.
                    Emits a MARKETING_TEMPLATE_DELETE event; the deletion is irreversible.
                    Returns 404 when the template does not exist, and 422 when it is still attached to at \
                    least one campaign.
                    """)
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Template deleted", content = @Content),
        @ApiResponse(responseCode = "404", description = "Template not found", content = @Content),
        @ApiResponse(responseCode = "422", description = "Template is attached to a campaign", content = @Content),
        @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions", content = @Content)
    })
    @DeleteMapping("/{templateId}")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {MarketingPermissionRegistry.TEMPLATE_MANAGE})
    @PreAuthorize("hasAuthority('marketing:template:manage')")
    @EmitEvent(id = "MARKETING_TEMPLATE_DELETE", apiVersion = "1")
    public ResponseEntity<Void> delete(@PathVariable UUID templateId) {
        templateService.delete(templateId);
        return ResponseEntity.noContent().build();
    }
}
