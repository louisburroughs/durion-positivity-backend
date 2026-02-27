package com.positivity.vehiclereferencecarapi.internal.service;

import com.positivity.vehiclereferencecarapi.internal.entity.CarApiMake;
import com.positivity.vehiclereferencecarapi.internal.entity.CarApiModel;
import com.positivity.vehiclereferencecarapi.internal.exception.CarApiException;
import com.positivity.vehiclereferencecarapi.internal.repository.CarApiMakeRepository;
import com.positivity.vehiclereferencecarapi.internal.repository.CarApiModelRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@RequiredArgsConstructor
@Service
public class VehicleReferenceService {
    private static final Duration CACHE_EXPIRY = Duration.ofHours(24);
    private final CarApiMakeRepository makeRepository;
    private final CarApiModelRepository modelRepository;
    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${carapi.base-url:https://carapi.app/api}")
    private String carApiBaseUrl;

    public List<CarApiMake> getMakes() {
        List<CarApiMake> cached = makeRepository.findAll();
        if (!cached.isEmpty() && isCacheExpired(cached.getFirst().getCacheTimestamp())) {
            return cached;
        }
        String url = carApiBaseUrl + "/makes";
        String response = restClient.get()
                .uri(url)
                .retrieve()
                .body(String.class);
        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode results = root.get("data");
            makeRepository.deleteAll();
            List<CarApiMake> makes = new ArrayList<>();
            for (JsonNode node : results) {
                CarApiMake make = new CarApiMake();
                make.setMakeId(node.path("id").asString(""));
                make.setMakeName(node.path("name").asString(""));
                make.setCacheTimestamp(LocalDateTime.now());
                makes.add(makeRepository.save(make));
            }
            return makes;
        } catch (Exception e) {
            throw new CarApiException("Failed to parse makes from CarAPI", e);
        }
    }

    public List<CarApiModel> getModelsByMakeId(UUID makeId) {
        List<CarApiModel> cached = modelRepository.findByMakeId(makeId);
        if (!cached.isEmpty() && isCacheExpired(cached.getFirst().getCacheTimestamp())) {
            return cached;
        }
        String url = carApiBaseUrl + "/models?make_id=" + makeId;
        String response = restClient.get()
                .uri(url)
                .retrieve()
                .body(String.class);
        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode results = root.get("data");
            modelRepository.deleteAll(cached);
            List<CarApiModel> models = new ArrayList<>();
            for (JsonNode node : results) {
                CarApiModel model = new CarApiModel();
                model.setModelId(UUID.fromString(node.path("id").asString()));
                model.setModelName(node.path("name").asString(""));
                model.setMakeId(makeId);
                model.setCacheTimestamp(LocalDateTime.now());
                models.add(modelRepository.save(model));
            }
            return models;
        } catch (Exception e) {
            throw new CarApiException("Failed to parse models from CarAPI", e);
        }
    }

    private boolean isCacheExpired(LocalDateTime cacheTimestamp) {
        return cacheTimestamp != null && !cacheTimestamp.plus(CACHE_EXPIRY).isBefore(LocalDateTime.now());
    }
}
