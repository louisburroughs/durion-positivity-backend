# pos-image

Image storage and retrieval microservice for the Durion Positivity ETSMS platform. Persists image binary data with classification metadata and provides fetch-by-filename or fetch-by-ID endpoints.

## Responsibilities

- Store uploaded images with a classification tag (product, vehicle, document, etc.)
- Retrieve images by internal UUID or by original filename
- Track image metadata (filename, content type, size) in PostgreSQL via JPA
- Support audit timestamps via JPA auditing

## Key Classes

- `ImageService` — public service interface; upload and retrieval operations
- `ImageServiceImpl` — stores image bytes in `image_entity` via `ImageRepository`
- `ImageController` — REST controller at `/v1/images`
- `ImageEntity` — JPA entity for image record (id, filename, contentType, data, classification)
- `Classification` — enum of image classification categories

## API Endpoints

- `GET /v1/images/id/{id}` — retrieve image by UUID
- `GET /v1/images/filename/{filename}` — retrieve image by original filename

## Configuration

| Property                | Default  | Description                  |
| ----------------------- | -------- | ---------------------------- |
| `SPRING_DATASOURCE_URL` | required | PostgreSQL connection URL    |
| `EUREKA_SERVER_URL`     | required | Eureka service discovery URL |

## Dependencies

No internal `pos-*` module dependencies at runtime.

## Database

Uses Flyway with PostgreSQL. Migrations at `src/main/resources/db/migration`.

## Development

```bash
./mvnw -pl pos-image -am spring-boot:run
```
