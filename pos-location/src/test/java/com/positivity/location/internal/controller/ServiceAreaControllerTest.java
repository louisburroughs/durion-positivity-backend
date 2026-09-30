package com.positivity.location.internal.controller;

import static com.positivity.location.config.LocationScopeTestSupport.as;
import static com.positivity.location.config.LocationScopeTestSupport.clearCaller;
import static com.positivity.location.config.LocationScopeTestSupport.preRollout;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.location.config.LocationScopeTestSupport;
import com.positivity.location.internal.entity.ServiceAreaEntity;
import com.positivity.location.internal.entity.ServiceAreaPostalCodeValue;
import com.positivity.location.internal.repository.ServiceAreaRepository;
import com.positivity.location.internal.security.LocationPermissions;
import com.positivity.location.internal.service.ServiceAreaServiceImpl;
import com.positivity.security.common.LocationScopeAutoConfiguration;
import com.positivity.web.common.WebCommonErrorAutoConfiguration;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Write-path proof for the service area endpoints (#2256): the request goes through the real
 * controller, bean validation, error advices and {@link ServiceAreaServiceImpl}; only the
 * repository is mocked. That is what lets each refusal assert both the status and {@code code} the
 * client sees and that {@code saveAndFlush} was never reached, so nothing was persisted.
 */
@WebMvcTest(ServiceAreaController.class)
@Import({
    ServiceAreaServiceImpl.class,
    LocationScopeAutoConfiguration.class,
    WebCommonErrorAutoConfiguration.class,
    LocationScopeTestSupport.SliceConfig.class
})
@ActiveProfiles("test")
class ServiceAreaControllerTest {

    private static final String URL = "/v1/service-areas";
    private static final String AREA_URL = "/v1/service-areas/{id}";
    private static final String POSTAL_CODES_URL = "/v1/service-areas/{id}/postal-codes";
    private static final UUID AREA_ID = UUID.fromString("019200bb-0000-7000-8000-000000000a01");
    private static final String VALIDATION_ERROR = "VALIDATION_ERROR";
    private static final String CODES = "\"postalCodes\":[{\"postalCode\":\"62704\",\"countryCode\":\"US\"}]";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ServiceAreaRepository repository;

    private ServiceAreaEntity existing;

    @BeforeEach
    void setUp() {
        Set<ServiceAreaPostalCodeValue> codes = new LinkedHashSet<>();
        codes.add(ServiceAreaPostalCodeValue.builder()
                .postalCode("62704")
                .countryCode("US")
                .build());
        existing = ServiceAreaEntity.builder()
                .id(AREA_ID)
                .name("North Metro")
                .description("old")
                .active(true)
                .postalCodes(codes)
                .build();
        as(preRollout(LocationPermissions.SERVICE_AREA_MANAGE));
    }

    @AfterEach
    void tearDown() {
        clearCaller();
    }

    private void areaExists() {
        when(repository.findById(AREA_ID)).thenReturn(Optional.of(existing));
        when(repository.saveAndFlush(any(ServiceAreaEntity.class))).thenAnswer(call -> call.getArgument(0));
    }

    private void nothingPersisted() {
        verify(repository, never()).saveAndFlush(any(ServiceAreaEntity.class));
        verify(repository, never()).save(any(ServiceAreaEntity.class));
    }

    private static DataIntegrityViolationException uniqueViolation(String constraint) {
        return new DataIntegrityViolationException(
                "could not execute statement",
                new RuntimeException("ERROR: duplicate key value violates unique constraint \"" + constraint + "\""));
    }

    // ------------------------------------------------------------------ create

    @Test
    @DisplayName("POST a valid area answers 201 and flushes the insert")
    void createFlushes() throws Exception {
        when(repository.saveAndFlush(any(ServiceAreaEntity.class))).thenAnswer(call -> call.getArgument(0));

        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"North\"," + CODES + "}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("North"))
                .andExpect(jsonPath("$.active").value(true));

        verify(repository).saveAndFlush(any(ServiceAreaEntity.class));
    }

    @Test
    @DisplayName("POST a duplicate name answers 409 SERVICE_AREA_NAME_TAKEN")
    void createDuplicateName() throws Exception {
        when(repository.saveAndFlush(any(ServiceAreaEntity.class)))
                .thenThrow(uniqueViolation("service_areas_name_key"));

        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"North\"," + CODES + "}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SERVICE_AREA_NAME_TAKEN"));
    }

    @Test
    @DisplayName("POST that trips a different constraint answers 409 SERVICE_AREA_CONFLICT, not a name clash")
    void createOtherConstraint() throws Exception {
        when(repository.saveAndFlush(any(ServiceAreaEntity.class))).thenThrow(uniqueViolation("service_areas_pkey"));

        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"North\"," + CODES + "}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SERVICE_AREA_CONFLICT"));
    }

    @Test
    @DisplayName("POST a blank name answers 400 VALIDATION_ERROR on name and persists nothing")
    void createBlankName() throws Exception {
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\" \"," + CODES + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(VALIDATION_ERROR))
                .andExpect(jsonPath("$.fieldErrors[*].field").value(hasItem("name")));

        nothingPersisted();
    }

    @Test
    @DisplayName("POST an over-long postalCode (21) answers 400 with a field error, not a DB 500")
    void createOverLongPostalCode() throws Exception {
        String body = "{\"name\":\"North\",\"postalCodes\":[{\"postalCode\":\"" + "1".repeat(21)
                + "\",\"countryCode\":\"US\"}]}";

        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(VALIDATION_ERROR))
                .andExpect(jsonPath("$.fieldErrors[*].field").value(hasItem("postalCodes[0].postalCode")));

        nothingPersisted();
    }

    @Test
    @DisplayName("POST an over-long countryCode (3) answers 400 with a field error")
    void createOverLongCountryCode() throws Exception {
        String body = "{\"name\":\"North\",\"postalCodes\":[{\"postalCode\":\"62704\",\"countryCode\":\"USA\"}]}";

        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(VALIDATION_ERROR))
                .andExpect(jsonPath("$.fieldErrors[*].field").value(hasItem("postalCodes[0].countryCode")));

        nothingPersisted();
    }

    // ------------------------------------------------------------------- patch

    @Test
    @DisplayName("PATCH name renames the area")
    void patchRename() throws Exception {
        areaExists();

        mockMvc.perform(patch(AREA_URL, AREA_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"South Metro\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("South Metro"));

        verify(repository).saveAndFlush(existing);
    }

    @Test
    @DisplayName("PATCH name onto a taken name answers 409 SERVICE_AREA_NAME_TAKEN")
    void patchRenameToTakenName() throws Exception {
        areaExists();
        when(repository.saveAndFlush(any(ServiceAreaEntity.class)))
                .thenThrow(uniqueViolation("service_areas_name_key"));

        mockMvc.perform(patch(AREA_URL, AREA_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Taken\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SERVICE_AREA_NAME_TAKEN"));
    }

    @Test
    @DisplayName("PATCH a blank, null or non-text name answers 400 VALIDATION_ERROR on name")
    void patchBadName() throws Exception {
        areaExists();

        for (String body : new String[] {"{\"name\":\"  \"}", "{\"name\":null}", "{\"name\":12}"}) {
            mockMvc.perform(patch(AREA_URL, AREA_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(VALIDATION_ERROR))
                    .andExpect(jsonPath("$.fieldErrors[0].field").value("name"));
        }

        nothingPersisted();
        assertThat(existing.getName()).isEqualTo("North Metro");
    }

    @Test
    @DisplayName("PATCH active null, \"yes\", 1 or \"true\" answers 400 on active and the area stays active")
    void patchBadActive() throws Exception {
        areaExists();

        for (String body :
                new String[] {"{\"active\":null}", "{\"active\":\"yes\"}", "{\"active\":1}", "{\"active\":\"true\"}"}) {
            mockMvc.perform(patch(AREA_URL, AREA_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(VALIDATION_ERROR))
                    .andExpect(jsonPath("$.fieldErrors[0].field").value("active"));
        }

        nothingPersisted();
        assertThat(existing.getActive()).isTrue();
    }

    @Test
    @DisplayName("PATCH active false retires the area; an absent key leaves it unchanged")
    void patchActiveBoolean() throws Exception {
        areaExists();

        mockMvc.perform(patch(AREA_URL, AREA_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"still here\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true));
        mockMvc.perform(patch(AREA_URL, AREA_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
    }

    @Test
    @DisplayName("PATCH a non-text description answers 400 on description instead of a 500")
    void patchNonTextDescription() throws Exception {
        areaExists();

        for (String body :
                new String[] {"{\"description\":5}", "{\"description\":[\"a\"]}", "{\"description\":true}"}) {
            mockMvc.perform(patch(AREA_URL, AREA_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(VALIDATION_ERROR))
                    .andExpect(jsonPath("$.fieldErrors[0].field").value("description"));
        }

        nothingPersisted();
        assertThat(existing.getDescription()).isEqualTo("old");
    }

    @Test
    @DisplayName("PATCH description null clears it")
    void patchDescriptionNullClears() throws Exception {
        areaExists();

        mockMvc.perform(patch(AREA_URL, AREA_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").doesNotExist());

        assertThat(existing.getDescription()).isNull();
    }

    @Test
    @DisplayName("PATCH a malformed id answers 400 and an unknown id answers 404")
    void patchBadOrUnknownId() throws Exception {
        when(repository.findById(AREA_ID)).thenReturn(Optional.empty());

        mockMvc.perform(patch(AREA_URL, "not-a-uuid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\":false}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(patch(AREA_URL, AREA_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\":false}"))
                .andExpect(status().isNotFound());

        nothingPersisted();
    }

    // ------------------------------------------------------------ postal codes

    @Test
    @DisplayName("PUT postal codes with an over-long entry answers 400 with a field error and persists nothing")
    void replaceOverLongEntry() throws Exception {
        areaExists();
        String body = "{\"postalCodes\":[{\"postalCode\":\"" + "1".repeat(21)
                + "\",\"countryCode\":\"US\"},{\"postalCode\":\"62711\",\"countryCode\":\"USA\"}]}";

        mockMvc.perform(put(POSTAL_CODES_URL, AREA_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(VALIDATION_ERROR))
                .andExpect(jsonPath("$.fieldErrors[*].field").value(hasItem("postalCodes[0].postalCode")))
                .andExpect(jsonPath("$.fieldErrors[*].field").value(hasItem("postalCodes[1].countryCode")));

        nothingPersisted();
    }

    @Test
    @DisplayName("PUT postal codes with a valid set answers 200 and flushes")
    void replaceValidSet() throws Exception {
        areaExists();

        mockMvc.perform(put(POSTAL_CODES_URL, AREA_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"postalCodes\":[{\"postalCode\":\"62711\",\"countryCode\":\"US\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.postalCodes[0].postalCode").value("62711"));

        verify(repository).saveAndFlush(existing);
    }
}
