package com.positivity.accounting;

import com.positivity.accounting.internal.dto.MappingKeyCreateRequest;
import com.positivity.accounting.internal.dto.MappingKeyUpdateRequest;
import com.positivity.accounting.internal.dto.PostingCategoryCreateRequest;
import com.positivity.accounting.internal.dto.PostingCategoryUpdateRequest;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Contract Behavioral Integration Tests for Posting Category and Mapping Key CRUD
 *
 * This test suite validates the behavioral contracts for CAP:050 - Posting Categories and Mapping Keys.
 *
 * Scope: Happy path, validation errors, uniqueness constraints, and deactivation rules
 *
 * Tests run against the actual service in test mode (not mocked).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("Posting Category and Mapping Key Contract Behavioral Tests")
public class PostingCategoryMappingKeyContractBehaviorIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private static final String API_V1 = "/v1/accounting";

    // ===============================================
    // POSTING CATEGORY - HAPPY PATH SCENARIOS
    // ===============================================

    @Test
    @DisplayName("PC-001: Create Posting Category with valid fields")
    public void testCreatePostingCategoryHappyPath() throws Exception {
        // Arrange: Prepare valid payload
        PostingCategoryCreateRequest request = new PostingCategoryCreateRequest();
        request.setCategoryName("Sales_" + UUID.randomUUID().toString().substring(0, 8));
        request.setDescription("Sales transactions");
        request.setCreatedBy("test-user");

        // Act: POST to Posting Category endpoint
        mockMvc.perform(post(API_V1 + "/posting-categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andDo(print())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.postingCategoryId").isNotEmpty())
                .andExpect(jsonPath("$.categoryName").value(request.getCategoryName()))
                .andExpect(jsonPath("$.description").value("Sales transactions"))
                .andExpect(jsonPath("$.isActive").value(true))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.createdBy").value("test-user"));
    }

    @Test
    @DisplayName("PC-002: Get Posting Category by ID")
    public void testGetPostingCategory() throws Exception {
        // Arrange: Create a posting category first
        UUID categoryId = createPostingCategory("Revenue_" + UUID.randomUUID().toString().substring(0, 8), 
                "Revenue transactions", "test-user");

        // Act & Assert: GET the category by ID
        mockMvc.perform(get(API_V1 + "/posting-categories/" + categoryId))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.postingCategoryId").value(categoryId.toString()));
    }

    @Test
    @DisplayName("PC-VE-001: Reject duplicate Posting Category name")
    public void testCreatePostingCategoryDuplicate() throws Exception {
        // Arrange: Create first posting category with unique name
        String uniqueName = "Inventory_" + UUID.randomUUID().toString().substring(0, 8);
        createPostingCategory(uniqueName, "Inventory transactions", "test-user");

        // Prepare duplicate
        PostingCategoryCreateRequest duplicate = new PostingCategoryCreateRequest();
        duplicate.setCategoryName(uniqueName);
        duplicate.setDescription("Another inventory category");
        duplicate.setCreatedBy("test-user");

        // Act & Assert: Expect BAD_REQUEST error
        mockMvc.perform(post(API_V1 + "/posting-categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(duplicate)))
                .andDo(print())
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PC-VE-002: Reject duplicate with different whitespace")
    public void testCreatePostingCategoryDuplicateWithWhitespace() throws Exception {
        // Arrange: Create first posting category
        String uniqueName = "Assets_" + UUID.randomUUID().toString().substring(0, 8);
        createPostingCategory(uniqueName, "Asset transactions", "test-user");

        // Prepare duplicate with leading/trailing whitespace
        PostingCategoryCreateRequest duplicate = new PostingCategoryCreateRequest();
        duplicate.setCategoryName(" " + uniqueName + " "); // Should be normalized
        duplicate.setDescription("Another asset category");
        duplicate.setCreatedBy("test-user");

        // Act & Assert: Expect BAD_REQUEST error due to uniqueness check
        mockMvc.perform(post(API_V1 + "/posting-categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(duplicate)))
                .andDo(print())
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PC-VE-003: Reject Get with non-existent ID")
    public void testGetNonExistentPostingCategory() throws Exception {
        // Arrange: Generate a random UUID that doesn't exist
        UUID nonExistentId = UUID.randomUUID();

        // Act & Assert: Expect NOT_FOUND error
        mockMvc.perform(get(API_V1 + "/posting-categories/" + nonExistentId))
                .andDo(print())
                .andExpect(status().isNotFound());
    }

    // ===============================================
    // MAPPING KEY - HAPPY PATH SCENARIOS
    // ===============================================

    @Test
    @DisplayName("MK-001: Create Mapping Key with valid fields")
    public void testCreateMappingKeyHappyPath() throws Exception {
        // Arrange: Create a posting category first
        UUID categoryId = createPostingCategory("Services_" + UUID.randomUUID().toString().substring(0, 8), 
                "Service transactions", "test-user");

        // Prepare mapping key request
        MappingKeyCreateRequest request = new MappingKeyCreateRequest();
        request.setPostingCategoryId(categoryId);
        request.setKeyName("Labor");
        request.setDescription("Labor services");
        request.setCreatedBy("test-user");

        // Act: POST to Mapping Key endpoint
        mockMvc.perform(post(API_V1 + "/mapping-keys")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andDo(print())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.mappingKeyId").isNotEmpty())
                .andExpect(jsonPath("$.postingCategoryId").value(categoryId.toString()))
                .andExpect(jsonPath("$.keyName").value("Labor"))
                .andExpect(jsonPath("$.description").value("Labor services"))
                .andExpect(jsonPath("$.isActive").value(true))
                .andExpect(jsonPath("$.createdAt").exists());
    }

    @Test
    @DisplayName("MK-002: Get Mapping Key by ID")
    public void testGetMappingKey() throws Exception {
        // Arrange: Create category and mapping key
        UUID categoryId = createPostingCategory("Materials_" + UUID.randomUUID().toString().substring(0, 8), 
                "Material transactions", "test-user");
        UUID keyId = createMappingKey(categoryId, "Steel", "Steel materials", "test-user");

        // Act & Assert: GET the mapping key by ID
        mockMvc.perform(get(API_V1 + "/mapping-keys/" + keyId))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mappingKeyId").value(keyId.toString()))
                .andExpect(jsonPath("$.postingCategoryId").value(categoryId.toString()))
                .andExpect(jsonPath("$.keyName").value("Steel"))
                .andExpect(jsonPath("$.description").value("Steel materials"));
    }

    @Test
    @DisplayName("MK-VE-001: Reject Mapping Key with non-existent Posting Category")
    public void testCreateMappingKeyNonExistentCategory() throws Exception {
        // Arrange: Generate a random UUID that doesn't exist
        UUID nonExistentCategoryId = UUID.randomUUID();

        MappingKeyCreateRequest request = new MappingKeyCreateRequest();
        request.setPostingCategoryId(nonExistentCategoryId);
        request.setKeyName("InvalidKey");
        request.setDescription("Invalid key");
        request.setCreatedBy("test-user");

        // Act & Assert: Expect NOT_FOUND error
        mockMvc.perform(post(API_V1 + "/mapping-keys")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andDo(print())
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("MK-VE-002: Reject duplicate Mapping Key name within same category")
    public void testCreateMappingKeyDuplicateWithinCategory() throws Exception {
        // Arrange: Create category and first mapping key
        UUID categoryId = createPostingCategory("Supplies_" + UUID.randomUUID().toString().substring(0, 8), 
                "Supply transactions", "test-user");
        createMappingKey(categoryId, "Paper", "Paper supplies", "test-user");

        // Prepare duplicate mapping key
        MappingKeyCreateRequest duplicate = new MappingKeyCreateRequest();
        duplicate.setPostingCategoryId(categoryId);
        duplicate.setKeyName("Paper");
        duplicate.setDescription("Another paper key");
        duplicate.setCreatedBy("test-user");

        // Act & Assert: Expect BAD_REQUEST error
        mockMvc.perform(post(API_V1 + "/mapping-keys")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(duplicate)))
                .andDo(print())
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("MK-VE-003: Allow same Mapping Key name in different categories")
    public void testCreateMappingKeySameNameDifferentCategory() throws Exception {
        // Arrange: Create two different categories
        UUID category1 = createPostingCategory("CategoryX_" + UUID.randomUUID().toString().substring(0, 8), 
                "Description X", "test-user");
        UUID category2 = createPostingCategory("CategoryY_" + UUID.randomUUID().toString().substring(0, 8), 
                "Description Y", "test-user");

        // Create same key name in first category
        createMappingKey(category1, "CommonKey", "Key in category X", "test-user");

        // Prepare same key name for second category
        MappingKeyCreateRequest request = new MappingKeyCreateRequest();
        request.setPostingCategoryId(category2);
        request.setKeyName("CommonKey");
        request.setDescription("Key in category Y");
        request.setCreatedBy("test-user");

        // Act & Assert: Expect success (keys can have same name in different categories)
        mockMvc.perform(post(API_V1 + "/mapping-keys")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andDo(print())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.keyName").value("CommonKey"));
    }

    // ===============================================
    // HELPER METHODS
    // ===============================================

    private UUID createPostingCategory(String name, String description, String createdBy) throws Exception {
        PostingCategoryCreateRequest request = new PostingCategoryCreateRequest();
        request.setCategoryName(name);
        request.setDescription(description);
        request.setCreatedBy(createdBy);

        MvcResult result = mockMvc.perform(post(API_V1 + "/posting-categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();

        String responseBody = result.getResponse().getContentAsString();

        @SuppressWarnings("unchecked")
        Map<String, Object> response = objectMapper.readValue(responseBody, Map.class);

        return UUID.fromString((String) response.get("postingCategoryId"));
    }

    private UUID createMappingKey(UUID categoryId, String keyName, String description, String createdBy) throws Exception {
        MappingKeyCreateRequest request = new MappingKeyCreateRequest();
        request.setPostingCategoryId(categoryId);
        request.setKeyName(keyName);
        request.setDescription(description);
        request.setCreatedBy(createdBy);

        MvcResult result = mockMvc.perform(post(API_V1 + "/mapping-keys")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();

        String responseBody = result.getResponse().getContentAsString();

        @SuppressWarnings("unchecked")
        Map<String, Object> response = objectMapper.readValue(responseBody, Map.class);

        return UUID.fromString((String) response.get("mappingKeyId"));
    }
}
