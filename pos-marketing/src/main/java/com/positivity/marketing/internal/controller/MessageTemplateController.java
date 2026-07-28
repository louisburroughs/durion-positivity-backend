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

    private final MessageTemplateService templateService;

    public MessageTemplateController(MessageTemplateService templateService) {
        this.templateService = templateService;
    }

    @Operation(summary = "List templates", description = "List templates, optionally filtered by channel and audience")
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

    @Operation(summary = "Get template", description = "Retrieve a template and its available tokens")
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

    @Operation(
            summary = "Create template",
            description =
                    "Create a template. Unknown tokens and tokens outside the audience's vocabulary are rejected.")
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
    public ResponseEntity<MessageTemplateResponse> create(@Valid @RequestBody UpsertMessageTemplateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(templateService.create(request));
    }

    @Operation(summary = "Update template", description = "Update a template's name, subject, or body")
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
            @PathVariable UUID templateId, @Valid @RequestBody UpsertMessageTemplateRequest request) {
        return ResponseEntity.ok(templateService.update(templateId, request));
    }

    @Operation(summary = "Delete template", description = "Delete a template that no campaign is using")
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
