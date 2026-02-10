# Vehicle Fitment Hints API

This module provides API endpoints for managing vehicle applicability hints and filtering products by vehicle attributes.

## Overview

The Vehicle Fitment Hints feature allows product administrators to associate products with vehicle-specific fitment information, enabling service advisors to filter and suggest compatible products for customer vehicles.

## Key Features

- **CRUD Operations**: Create, Read, Update, and Delete vehicle applicability hints
- **Product Filtering**: Filter products by vehicle attributes (make, model, year range, etc.)
- **Audit Logging**: All operations are logged with audit events
- **Flexible Tag System**: Support for multiple tag types including MAKE, MODEL, YEAR_RANGE, TIRE_SIZE, AXLE_POSITION, ENGINE_SIZE, and TRIM_LEVEL

## API Endpoints

### Create a Hint
**POST** `/api/vehicle-fitment/hints`

Create a new vehicle applicability hint for a product.

**Request Body:**
```json
{
  "productId": 123,
  "fitmentTags": [
    {
      "tagType": "MAKE",
      "tagValue": "Toyota"
    },
    {
      "tagType": "MODEL",
      "tagValue": "Camry"
    },
    {
      "tagType": "YEAR_RANGE",
      "tagValue": "2018-2022"
    }
  ],
  "createdBy": "admin"
}
```

**Response:** `201 Created`
```json
{
  "hintId": 1,
  "productId": 123,
  "fitmentTags": [...],
  "createdAt": "2024-01-12T10:00:00",
  "updatedAt": "2024-01-12T10:00:00",
  "createdBy": "admin",
  "updatedBy": "admin"
}
```

### Update a Hint
**PUT** `/api/vehicle-fitment/hints/{hintId}`

Update an existing vehicle applicability hint.

**Request Body:**
```json
{
  "fitmentTags": [
    {
      "tagType": "YEAR_RANGE",
      "tagValue": "2018-2024"
    }
  ],
  "updatedBy": "admin"
}
```

**Response:** `200 OK`

### Delete a Hint
**DELETE** `/api/vehicle-fitment/hints/{hintId}`

Delete a vehicle applicability hint.

**Response:** `204 No Content`

### Get a Hint
**GET** `/api/vehicle-fitment/hints/{hintId}`

Retrieve a specific hint by its ID.

**Response:** `200 OK`

### Get Hints by Product
**GET** `/api/vehicle-fitment/hints/product/{productId}`

Retrieve all hints associated with a specific product.

**Response:** `200 OK`
```json
[
  {
    "hintId": 1,
    "productId": 123,
    "fitmentTags": [...],
    "createdAt": "2024-01-12T10:00:00",
    "updatedAt": "2024-01-12T10:00:00",
    "createdBy": "admin",
    "updatedBy": "admin"
  }
]
```

### Filter Products by Vehicle Attributes
**POST** `/api/vehicle-fitment/hints/filter-products`

Find products that match the provided vehicle attributes.

**Request Body:**
```json
{
  "make": "Toyota",
  "model": "Camry",
  "year": "2020"
}
```

**Response:** `200 OK`
```json
{
  "productIds": [123, 456, 789],
  "count": 3
}
```

## Tag Types

The following tag types are supported:

- **MAKE**: Vehicle manufacturer (e.g., "Toyota", "Honda")
- **MODEL**: Vehicle model (e.g., "Camry", "Civic")
- **YEAR_RANGE**: Applicable year range (e.g., "2018-2022" or "2020")
- **TIRE_SIZE**: Tire size specification (e.g., "225/45R17")
- **AXLE_POSITION**: Axle position (e.g., "FRONT", "REAR")
- **ENGINE_SIZE**: Engine size (e.g., "2.0L", "3.5L")
- **TRIM_LEVEL**: Trim level (e.g., "LX", "SE", "Limited")

## Matching Logic

### Basic Matching
A product matches if its applicability hints contain tags that are compatible with all provided vehicle attributes.

### Year Range Matching
- Single year: "2020" matches exactly 2020
- Range: "2018-2022" matches years 2018, 2019, 2020, 2021, and 2022

### Case-Insensitive Matching
All string comparisons are case-insensitive.

## Audit Events

The following audit events are generated:

- `VEHICLE_HINT_CREATED`: When a new hint is created
- `VEHICLE_HINT_UPDATED`: When an existing hint is updated
- `VEHICLE_HINT_DELETED`: When a hint is deleted

## Error Responses

- **400 Bad Request**: Invalid request data or malformed tags
- **404 Not Found**: Hint or product not found
- **200 OK with empty list**: No matching products found

## Database Schema

### VehicleApplicabilityHint
- `hintId` (PK): Unique identifier
- `productId`: Reference to product/SKU
- `createdAt`: Timestamp
- `updatedAt`: Timestamp
- `createdBy`: User who created the hint
- `updatedBy`: User who last updated the hint

### FitmentTag
- `id` (PK): Unique identifier
- `tagType`: Type of tag (enum)
- `tagValue`: Value of the tag
- `hint_id` (FK): Reference to VehicleApplicabilityHint

## Example Usage

### Creating a Hint for a Tire Product

```bash
curl -X POST http://localhost:8088/api/vehicle-fitment/hints \
  -H "Content-Type: application/json" \
  -d '{
    "productId": 1001,
    "fitmentTags": [
      {"tagType": "MAKE", "tagValue": "Honda"},
      {"tagType": "MODEL", "tagValue": "Civic"},
      {"tagType": "YEAR_RANGE", "tagValue": "2016-2021"},
      {"tagType": "TIRE_SIZE", "tagValue": "215/55R16"}
    ],
    "createdBy": "admin"
  }'
```

### Filtering Products for a Specific Vehicle

```bash
curl -X POST http://localhost:8088/api/vehicle-fitment/hints/filter-products \
  -H "Content-Type: application/json" \
  -d '{
    "make": "Honda",
    "model": "Civic",
    "year": "2019"
  }'
```

## Testing

Run the unit tests:

```bash
mvn test -pl pos-vehicle-fitment
```

## Dependencies

- Spring Boot 4.0.2
- Spring Data JPA
- H2 Database (runtime)
- Lombok
- Spring Boot Validation
- pos-events (audit logging)

## Notes

- This is a basic implementation intended as a filtering hint, not a comprehensive fitment engine
- The hints serve as suggestions and should not block transactions if a service advisor chooses to override
- The system allows for extensibility with new tag types without schema changes
