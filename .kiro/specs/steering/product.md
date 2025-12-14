# Product Overview

## Positivity POS System

Positivity is a modular Point of Sale (POS) system designed for infinite scaling with a "pay as you grow" model. Built using Spring Boot microservices architecture, it provides enterprise-grade functionality for retail, automotive, and service-based businesses.

## Core Business Domains

- **Catalog Management (pos-catalog)** - Product catalog, categories, and item management
- **Customer Management (pos-customer)** - Customer profiles, preferences, and relationship management
- **Inventory Management (pos-inventory, pos-vehicle-inventory)** - Stock tracking, warehouse management, and vehicle-specific inventory
- **Order Processing (pos-order, pos-invoice)** - Order lifecycle, invoicing, and payment processing
- **Pricing & Accounting (pos-price, pos-accounting)** - Dynamic pricing, financial tracking, and reporting
- **Work Orders (pos-work-order)** - Service scheduling, task management, and completion tracking
- **Vehicle Services (pos-vehicle-fitment, pos-vehicle-reference-*)** - Automotive parts fitment and vehicle data integration
- **Location Management (pos-location)** - Multi-location support and geographic services
- **People Management (pos-people)** - Staff, roles, and organizational structure

## Key Characteristics

- **Microservices Architecture** - Independent, scalable services with separate data stores
- **Shared Security** - Centralized authentication and authorization via pos-security-service
- **API Gateway** - Single entry point for all client applications via pos-api-gateway
- **Service Discovery** - Dynamic service registration and discovery via pos-service-discovery
- **Event-Driven** - Asynchronous communication through pos-events and pos-event-receiver
- **Multi-Database Support** - Each service manages its own dedicated database
- **Cloud-Native** - Built for containerization and cloud deployment
- **Observability** - Centralized metrics collection and monitoring

## Architecture Principles

- **Module Isolation** - Each business domain is a self-contained microservice
- **Shared Security** - Unified authentication across all modules
- **Centralized Metrics** - Aggregated monitoring and alerting
- **Experience Layer Support** - Multiple client types (web, mobile, external APIs)
- **Infinite Scalability** - Pay-as-you-grow model with independent service scaling

## Target Use Cases

- Retail point of sale systems
- Automotive parts and service centers
- Multi-location retail chains
- Service-based businesses with work order management
- B2B wholesale and distribution
- Custom POS solutions requiring rapid feature development