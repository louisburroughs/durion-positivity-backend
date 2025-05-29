package com.positivity.vehiclereferencecarapi.service;

import com.positivity.vehiclereferencecarapi.entity.CarApiMake;
import com.positivity.vehiclereferencecarapi.entity.CarApiModel;
import com.positivity.vehiclereferencecarapi.repository.CarApiMakeRepository;
import com.positivity.vehiclereferencecarapi.repository.CarApiModelRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
public class VehicleReferenceService {
    private static final Duration CACHE_EXPIRY = Duration.ofHours(24);
    private final CarApiMakeRepository makeRepository;
    private final CarApiModelRepository modelRepository;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${carapi.base-url:https://carapi.app/api}")
    private String carApiBaseUrl;

    public VehicleReferenceService(CarApiMakeRepository makeRepository, CarApiModelRepository modelRepository, RestTemplate restTemplate) {
        this.makeRepository = makeRepository;
        this.modelRepository = modelRepository;
        this.restTemplate = restTemplate;
    }

    public List<CarApiMake> getMakes() {
        List<CarApiMake> cached = makeRepository.findAll();
        if (!cached.isEmpty() && !isCacheExpired(cached.get(0).getCacheTimestamp())) {
            return cached;
        }
        String url = carApiBaseUrl + "/makes";
        String response = restTemplate.getForObject(url, String.class);
        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode results = root.get("data");
            makeRepository.deleteAll();
            List<CarApiMake> makes = new ArrayList<>();
            for (JsonNode node : results) {
                CarApiMake make = new CarApiMake();
                make.setMakeId(node.path("id").asText(""));
                make.setMakeName(node.path("name").asText(""));
                make.setCacheTimestamp(LocalDateTime.now());
                makes.add(makeRepository.save(make));
            }
            return makes;
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse makes from CarAPI", e);
        }
    }

    public List<CarApiModel> getModelsByMakeId(String makeId) {
        List<CarApiModel> cached = modelRepository.findByMakeId(makeId);
        if (!cached.isEmpty() && !isCacheExpired(cached.get(0).getCacheTimestamp())) {
            return cached;
        }
        String url = carApiBaseUrl + "/models?make_id=" + makeId;
        String response = restTemplate.getForObject(url, String.class);
        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode results = root.get("data");
            modelRepository.deleteAll(cached);
            List<CarApiModel> models = new ArrayList<>();
            for (JsonNode node : results) {
                CarApiModel model = new CarApiModel();
                model.setModelId(node.path("id").asText(""));
                model.setModelName(node.path("name").asText(""));
                model.setMakeId(makeId);
                model.setCacheTimestamp(LocalDateTime.now());
                models.add(modelRepository.save(model));
            }
            return models;
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse models from CarAPI", e);
        }
    }

    private boolean isCacheExpired(LocalDateTime cacheTimestamp) {
        return cacheTimestamp == null || cacheTimestamp.plus(CACHE_EXPIRY).isBefore(LocalDateTime.now());
    }
}

