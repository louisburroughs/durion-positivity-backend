## 🏷️ Labels (Proposed)

### Required
- type:story
- domain:inventory
- status:ready-for-dev

### Recommended
- agent:inventory
- agent:story-authoring

---
**Rewrite Variant:** inventory-flexible
---

## Story Intent
As a **POS Clerk**, I need a fast and flexible catalog search capability to efficiently find products and services by various attributes (keyword, SKU, category, etc.) and add them to a customer's work order.

## Actors & Stakeholders
- **Primary Actor**: `POS Clerk` — The user performing the search via the POS terminal.
- **System Actors**: 
    - `POS Terminal` — The client application initiating the search request.
    - `Inventory Service` — The backend service responsible for executing the search against the product catalog.
- **Stakeholders**:
    - `Work Execution Domain` — Consumes the search results to build a work order/ticket.
    - `Product Domain` — The system of record for product definitions, specifications, and categories.

## Preconditions
- The POS Clerk is authenticated and has an active session on the POS Terminal.
- The POS Terminal is in a state that permits adding items to a work order or sale.
- The Inventory Service is available and reachable by the POS Terminal.

## Functional Behavior
1.  **Trigger:** The POS Clerk enters search criteria (e.g., keyword, SKU) and/or applies filters in the POS application's catalog search interface and initiates the search.
2.  The POS Terminal constructs and sends a search request to the Inventory Service API. The request includes the search terms, selected filters, and pagination parameters.
3.  The Inventory Service validates the search request.
4.  The service executes the query against the product catalog data, applying all specified criteria and filters.
5.  The service returns a paginated list of `ProductSummary` objects that match the query. The response includes metadata such as total item count, current page, and pagination cursor.
6.  The POS Terminal receives the response and displays the formatted results to the POS Clerk.

## Alternate / Error Flows
- **No Results Found:** If the query yields no matching items, the service returns an empty result list. The POS Terminal should display a "No results found" message to the Clerk.
- **Invalid Search Parameters:** If the search request is malformed or contains invalid data (e.g., negative price range), the service must reject it with a `400 Bad Request` status and a descriptive error message.
- **Service Unavailability:** If the Inventory Service is down or fails to respond, the POS Terminal must handle the error gracefully, displaying an appropriate message to the Clerk (e.g., "Catalog search is temporarily unavailable").

## Business Rules

### Identifier Precedence
- Searches for an exact SKU or Manufacturer Part Number (MPN) must be prioritized and return the specific item as the top result if a match exists.

### Keyword Matching
- Keyword searches must be case-insensitive.
- Keyword searches match against the following fields, in priority order:
  1. **SKU / Product Code** (exact + prefix matching)
  2. **Product Name** (tokenized contains)
  3. **Manufacturer Part Number (MPN)** (exact + prefix + tokenized)
  4. **Manufacturer / Brand Name** (tokenized)
  5. **Tags / Categories** (tokenized)
  6. **Short Description** (tokenized, lower weight)
- **Exclusions:** Long Description and free-form notes are excluded from keyword search.
- **Normalization:** Lowercase, strip punctuation, collapse whitespace.
- **Ranking:** Exact matches > prefix matches > token contains.
- **Weighting:** Identifiers (SKU/MPN) have highest weight; descriptions have lowest weight.

### Filter Logic
- All applied filters (e.g., manufacturer, price range, tire size) are to be combined using a logical `AND` operation.

### Price Range
- The price range filter (`minPrice`, `maxPrice`) is inclusive of the boundary values.

### Pagination Policy
- **Default page size:** 25 items per page
- **Maximum page size:** 100 items per page (hard cap)
- **Mechanism:** Cursor-based pagination for stability and performance
  - Request parameters: `cursor` (optional), `pageSize` (optional, default=25, max=100)
  - Response includes: `nextCursor` (null if no more pages)
- **Offset-based pagination:** May be supported separately for specific UI requirements, but cursor-based is the primary API mechanism.

### Performance Requirements
- **P95 response time:** < 500ms for search-only (catalog) operations
- **P99 response time:** < 1000ms for search-only operations
- **P50 response time:** < 200ms for search-only operations
- **Note:** If the endpoint performs dependency fan-out (pricing/inventory enrichment), separate SLOs apply:
  - **Search + enrichment P95:** < 900ms with graceful degradation when dependencies time out
- **Assumptions:** Targets assume indexed search (database indexes or search engine) and warm caches.

## Data Requirements

### Search Request (`SearchQuery`)
- `query_term` (string, optional): A keyword, SKU, or MPN.
- `category_id` (string, optional): The unique identifier for a product category.
- `filters` (object, optional):
    - `manufacturer` (string, optional)
    - `price_range` (object, optional): `{ min: decimal, max: decimal }`
    - `tire_specs` (object, optional): `{ width: int, aspect_ratio: int, diameter: int, ... }`
- `pagination` (object, optional): 
    - `cursor` (string, optional): Pagination cursor from previous response
    - `page_size` (integer, optional): Number of results (default=25, max=100)

### Search Response (`SearchResult`)
- `metadata`: 
    - `total_items` (integer): Total number of matching items
    - `page_size` (integer): Number of items in current page
    - `next_cursor` (string, nullable): Cursor for next page (null if last page)
- `items`: An array of `ProductSummary` objects.

### Product Summary (`ProductSummary`)
- `product_id` (string, UUID): Unique identifier for the product.
- `sku` (string): The Stock Keeping Unit.
- `mpn` (string, optional): The Manufacturer Part Number.
- `name` (string): The display name of the product.
- `description_short` (string): A brief product description.
- `list_price` (decimal): The standard retail price.
- `manufacturer` (string): The name of the manufacturer.
- `availability_hint` (enum): A high-level indicator of stock (e.g., `IN_STOCK`, `LOW_STOCK`, `OUT_OF_STOCK`, `ON_ORDER`).

## Acceptance Criteria

### Keyword Search
- **Given** a POS Clerk is viewing the catalog search screen
  **When** they enter a keyword that exists in a product's name or short description
  **Then** the search results must contain that product.

### SKU Priority
- **Given** a POS Clerk is viewing the catalog search screen
  **When** they enter an exact SKU for an existing product
  **Then** that product must be returned as the first result.

### Manufacturer Filter
- **Given** a search has returned a list of products from multiple manufacturers
  **When** the Clerk applies a filter for a single manufacturer
  **Then** the updated result list must only contain products from that specific manufacturer.

### Pagination Default
- **Given** a search query matches more than 25 products
  **When** the search is executed without specifying a page size
  **Then** the response must contain exactly 25 results (the default page size) and include a `next_cursor` in the metadata indicating more pages are available.

### Pagination Maximum
- **Given** a POS Clerk requests a page size of 150
  **When** the search is executed
  **Then** the service must return at most 100 results (the maximum page size) and not fail.

### No Results
- **Given** a POS Clerk is viewing the catalog search screen
  **When** they enter a search term that does not match any product
  **Then** the system must return an empty result set with `total_items: 0` and display a "No results found" message.

### Performance Target
- **Given** a valid search request is submitted to the Inventory Service
  **When** the service processes the request (search-only, no enrichment)
  **Then** the P95 response time must be under 500ms and the P99 response time must be under 1000ms.

### Keyword Field Matching
- **Given** a product exists with the name "Michelin Pilot Sport 4S"
  **When** a Clerk searches for "pilot sport"
  **Then** that product must appear in the search results (tokenized matching on Product Name).

### Exclusion of Long Description
- **Given** a product exists with a short description "Premium tire" and a long description containing the word "experimental"
  **When** a Clerk searches for "experimental"
  **Then** that product must NOT appear in the results (Long Description is excluded from search).

## Audit & Observability

### Logging
- All search API requests must be logged with their parameters (excluding any PII).
- API responses, especially errors (`4xx`, `5xx`), must be logged.
- The identity of the requesting user/clerk should be included in logs for audit purposes.

### Metrics
- `search_requests_total`: A counter for the number of search requests, partitioned by result type (success, no_results, error).
- `search_request_latency_seconds`: A histogram measuring the duration of search requests.
- `search_error_rate`: The percentage of requests that result in a `5xx` server error.
- `search_results_count`: A histogram of the number of results returned per search.

---

## Clarification History

**Clarification Issue:** [#219](https://github.com/louisburroughs/durion-positivity-backend/issues/219)

**Questions Resolved:**
1. **Performance:** P95 < 500ms, P99 < 1000ms, P50 < 200ms (search-only mode)
2. **Search Fields:** SKU, Product Name, MPN, Manufacturer/Brand, Tags/Categories, Short Description (exclude Long Description)
3. **Pagination:** Default 25, max 100, cursor-based with nextCursor

**Answered By:** @louisburroughs on 2026-01-12

---

## Original Story (Unmodified – For Traceability)
# Issue #17 — [BACKEND] [STORY] Catalog: Search Catalog by Keyword/SKU and Filter

## Current Labels
- backend
- story-implementation
- user

## Current Body
## Backend Implementation for Story

**Original Story**: [STORY] Catalog: Search Catalog by Keyword/SKU and Filter

**Domain**: user

### Story Description

/kiro
# User Story

## Narrative
As a **POS Clerk**, I want to search the catalog quickly so that I can find the right items during checkout.

## Details
- Search by keyword, SKU/MPN, category.
- Filters: tire size/spec, manufacturer, price range (basic).

## Acceptance Criteria
- Search returns results within target latency.
- Filters apply correctly.
- Pagination supported.

## Integrations
- Product domain provides definitions; inventory provides availability hints.

## Data / Entities
- SearchQuery, SearchResult, ProductSummary

## Classification (confirm labels)
- Type: Story
- Layer: Experience
- domain :  Point of Sale

### Backend Requirements

- Implement Spring Boot microservices
- Create REST API endpoints
- Implement business logic and data access
- Ensure proper security and validation

### Technical Stack

- Spring Boot 3.2.6
- Java 21
- Spring Data JPA
- PostgreSQL/MySQL

---
*This issue was automatically created by the Durion Workspace Agent*
