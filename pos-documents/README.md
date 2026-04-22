# pos-documents

PDF rendering microservice for the Durion POS platform. Accepts render requests with a template ID, data payload, and desired output format, then returns the rendered document. Templates are registered by consumer services at startup using `pos-document-helper`.

## Responsibilities

- Render HTML templates to PDF, HTML, TEXT, or CSV format
- Store and retrieve document templates by template ID
- Expose a single render endpoint consumed by all services that generate documents
- Emit audit events for render operations via `pos-events`

## Key Classes

- `PdfRenderingService` — public service interface; delegates to the format-specific handler
- `PdfRenderingServiceImpl` — coordinates template lookup and format handler invocation
- `TemplateService` — loads and caches registered HTML templates
- `DocumentRenderController` — REST controller at `/v1/documents`
- `DocumentFormat` — enum of supported output formats: `PDF`, `HTML`, `TEXT`, `CSV`

## API Endpoints

- `POST /v1/documents/render` — render a document; returns bytes in the requested MIME type

Request body: `{ "templateId": "...", "format": "PDF", "data": { ... } }`

## Configuration

| Property                             | Default                 | Description                          |
| ------------------------------------ | ----------------------- | ------------------------------------ |
| `documents.pdf.max-input-characters` | `1000000`               | Maximum template + data characters   |
| `documents.pdf.template-base-path`   | `classpath:/templates`  | Classpath root for template files    |
| `documents.pdf.max-table-rows`       | `200`                   | Maximum rows in table-type templates |
| `pos.events.base-url`                | `http://localhost:8085` | Event receiver URL                   |

## Dependencies

- `pos-security-common` — JWT-based security filter
- `pos-events` — `@EmitEvent` annotation and event registration

## Development

```bash
./mvnw -pl pos-documents -am spring-boot:run
```
