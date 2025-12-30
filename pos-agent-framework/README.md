# POS Agent Framework

## Overview

The POS Agent Framework provides intelligent development assistance across all aspects of the microservices architecture.

## Available Agents

The framework includes 15 specialized agents:

### Core Development Agents
- **Architecture Agent** - System Architecture & Design
- **Implementation Agent** - Spring Boot Development
- **Deployment Agent** - DevOps & Infrastructure
- **Testing Agent** - Quality Assurance & Testing
- **Security Agent** - Security & Compliance
- **Observability Agent** - Monitoring & Reliability
- **Documentation Agent** - Technical Documentation
- **Business Domain Agent** - POS Business Logic
- **Integration Gateway Agent** - API Gateway & Integration
- **Pair Programming Navigator Agent** - Code Quality & Collaboration

### Specialized Agents
- **Event-Driven Architecture Agent** - Event-Driven Systems
- **CI/CD Pipeline Agent** - Continuous Integration/Deployment
- **Configuration Management Agent** - Configuration & Secrets
- **Resilience Engineering Agent** - System Reliability & Resilience

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
