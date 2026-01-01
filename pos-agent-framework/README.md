# POS Agent Framework

## Overview

The POS Agent Framework provides intelligent development assistance across all aspects of the microservices architecture.

## Available Agents

The framework includes 14 specialized agents:

### Core Development Agents
- **Architecture Agent** (`architecture`) - System Architecture & Design
- **Implementation Agent** (`implementation`) - Spring Boot Development
- **Deployment Agent** (`deployment`) - DevOps & Infrastructure
- **Testing Agent** (`testing`) - Quality Assurance & Testing
- **Security Agent** (`security`) - Security & Compliance
- **Observability Agent** (`observability`) - Monitoring & Reliability
- **Documentation Agent** (`documentation`) - Technical Documentation
- **Business Domain Agent** (`business-domain`) - POS Business Logic
- **Integration Gateway Agent** (`integration-gateway`) - API Gateway & Integration
- **Pair Programming Navigator Agent** (`pair-programming-navigator`) - Code Quality & Collaboration

### Specialized Agents
- **Event-Driven Architecture Agent** (`event-driven-architecture`) - Event-Driven Systems
- **CI/CD Pipeline Agent** (`cicd-pipeline`) - Continuous Integration/Deployment
- **Configuration Management Agent** (`configuration-management`) - Configuration & Secrets
- **Resilience Engineering Agent** (`resilience-engineering`) - System Reliability & Resilience

## Configuration

Agent configurations are managed through environment-specific YAML files:
- `application.yml` - Base configuration
- `application-dev.yml` - Development environment
- `application-staging.yml` - Staging environment
- `application-prod.yml` - Production environment

## Deployment

### Docker
```bash
docker-compose up pos-agent-framework
```

### Kubernetes
```bash
kubectl apply -f pos-agent-framework/k8s-deployment.yml
```

## Usage Examples

Agents are automatically selected based on request context and domain requirements.

## API Documentation

API documentation is available through Spring Boot Actuator endpoints.
