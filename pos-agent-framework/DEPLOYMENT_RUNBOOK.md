# Agent Framework Production Deployment Runbook

## Overview

This runbook provides step-by-step procedures for deploying, monitoring, and maintaining the Positivity POS Agent Framework in production environments.

## Prerequisites

### Infrastructure Requirements
- Kubernetes cluster (v1.24+) with at least 3 nodes
- Minimum 8GB RAM and 4 CPU cores per node
- Persistent storage for monitoring data
- Load balancer for external access
- TLS certificates for secure communication

### Required Services
- Eureka Server for service discovery
- Kafka cluster for event streaming
- RabbitMQ cluster for messaging
- PostgreSQL database for agent state
- Redis for caching and session storage

## Deployment Procedures

### 1. Pre-Deployment Checklist

```bash
# Verify cluster connectivity
kubectl cluster-info

# Check node resources
kubectl top nodes

# Verify required namespaces
kubectl get namespaces

# Check persistent volumes
kubectl get pv
```

### 2. Initial Deployment

```bash
# Create namespace
kubectl create namespace pos-agents

# Apply secrets (update with actual values)
kubectl apply -f - <<EOF
apiVersion: v1
kind: Secret
metadata:
  name: agent-secrets
  namespace: pos-agents
type: Opaque
data:
  rabbitmq.username: $(echo -n "your-username" | base64)
  rabbitmq.password: $(echo -n "your-password" | base64)
  database.password: $(echo -n "your-db-password" | base64)
EOF

# Apply ConfigMaps
kubectl apply -f k8s-deployment.yml -n pos-agents

# Deploy monitoring stack
kubectl apply -f monitoring.yml -n pos-agents

# Deploy agent framework
kubectl apply -f k8s-deployment.yml -n pos-agents
```

### 3. Deployment Verification

```bash
# Check pod status
kubectl get pods -n pos-agents

# Verify services
kubectl get services -n pos-agents

# Check logs
kubectl logs -f deployment/pos-agent-framework -n pos-agents

# Test health endpoints
kubectl port-forward service/pos-agent-framework 8080:8080 -n pos-agents
curl http://localhost:8080/actuator/health
```

### 4. Rolling Updates

```bash
# Build new image
docker build -t pos-agent-framework:v1.1.0 .

# Tag and push to registry
docker tag pos-agent-framework:v1.1.0 your-registry/pos-agent-framework:v1.1.0
docker push your-registry/pos-agent-framework:v1.1.0

# Update deployment
kubectl set image deployment/pos-agent-framework pos-agent-framework=your-registry/pos-agent-framework:v1.1.0 -n pos-agents

# Monitor rollout
kubectl rollout status deployment/pos-agent-framework -n pos-agents

# Rollback if needed
kubectl rollout undo deployment/pos-agent-framework -n pos-agents
```

## Monitoring and Alerting

### Key Metrics to Monitor

1. **Agent Response Time**
   - Target: ≤ 500ms for 95% of requests
   - Alert: > 500ms for 2 minutes

2. **System Response Time**
   - Target: ≤ 3 seconds for 99% of requests
   - Alert: > 3 seconds for 1 minute

3. **Memory Usage**
   - Target: ≤ 2GB per instance
   - Alert: > 80% of limit for 5 minutes

4. **Error Rate**
   - Target: < 1% error rate
   - Alert: > 5% error rate for 2 minutes

5. **Agent Availability**
   - Target: 99.9% uptime
   - Alert: Service down for > 1 minute

### Grafana Dashboards

Access Grafana at the LoadBalancer IP on port 3000:
- Username: admin
- Password: (from grafana-secrets)

Import the following dashboard queries:

```promql
# Agent Response Time (95th percentile)
histogram_quantile(0.95, rate(http_request_duration_seconds_bucket{job="pos-agent-framework"}[5m]))

# Memory Usage Percentage
(container_memory_usage_bytes{pod=~"pos-agent-framework.*"} / container_spec_memory_limit_bytes) * 100

# Error Rate
rate(http_requests_total{job="pos-agent-framework",status=~"5.."}[5m]) * 100

# Active Agents Count
agent_registry_active_agents_total

# Request Rate
rate(http_requests_total{job="pos-agent-framework"}[5m])
```

## Operational Procedures

### Scaling Operations

```bash
# Scale up for high load
kubectl scale deployment pos-agent-framework --replicas=5 -n pos-agents

# Scale down during low usage
kubectl scale deployment pos-agent-framework --replicas=2 -n pos-agents

# Auto-scaling (HPA)
kubectl autoscale deployment pos-agent-framework --cpu-percent=70 --min=2 --max=10 -n pos-agents
```

### Backup Procedures

```bash
# Backup agent configurations
kubectl get configmap agent-config -n pos-agents -o yaml > agent-config-backup.yml

# Backup secrets (encrypted)
kubectl get secret agent-secrets -n pos-agents -o yaml > agent-secrets-backup.yml

# Backup monitoring configuration
kubectl get configmap agent-monitoring-config -n pos-agents -o yaml > monitoring-config-backup.yml
```

### Disaster Recovery

1. **Service Failure Recovery**
```bash
# Check pod status
kubectl get pods -n pos-agents

# Restart failed pods
kubectl delete pod <failed-pod-name> -n pos-agents

# Check events for issues
kubectl get events -n pos-agents --sort-by='.lastTimestamp'
```

2. **Complete Service Recovery**
```bash
# Delete and recreate deployment
kubectl delete deployment pos-agent-framework -n pos-agents
kubectl apply -f k8s-deployment.yml -n pos-agents

# Verify recovery
kubectl get pods -n pos-agents -w
```

3. **Data Recovery**
```bash
# Restore from backups
kubectl apply -f agent-config-backup.yml -n pos-agents
kubectl apply -f agent-secrets-backup.yml -n pos-agents

# Restart services to pick up restored config
kubectl rollout restart deployment/pos-agent-framework -n pos-agents
```

## Security Procedures

### Certificate Management

```bash
# Check certificate expiration
kubectl get secret tls-secret -n pos-agents -o jsonpath='{.data.tls\.crt}' | base64 -d | openssl x509 -noout -dates

# Update certificates
kubectl create secret tls tls-secret --cert=path/to/tls.crt --key=path/to/tls.key -n pos-agents --dry-run=client -o yaml | kubectl apply -f -
```

### Security Scanning

```bash
# Scan container images
docker run --rm -v /var/run/docker.sock:/var/run/docker.sock aquasec/trivy image pos-agent-framework:latest

# Check for vulnerabilities
kubectl run security-scan --image=aquasec/kube-bench:latest --rm -it --restart=Never -- --version 1.24
```

## Troubleshooting Guide

### Common Issues

1. **Agent Not Responding**
```bash
# Check pod logs
kubectl logs -f deployment/pos-agent-framework -n pos-agents

# Check resource usage
kubectl top pods -n pos-agents

# Describe pod for events
kubectl describe pod <pod-name> -n pos-agents
```

2. **High Memory Usage**
```bash
# Check JVM heap usage
kubectl exec -it <pod-name> -n pos-agents -- jcmd 1 GC.run_finalization
kubectl exec -it <pod-name> -n pos-agents -- jcmd 1 VM.memory_summary

# Restart pod if needed
kubectl delete pod <pod-name> -n pos-agents
```

3. **Service Discovery Issues**
```bash
# Check Eureka connectivity
kubectl exec -it <pod-name> -n pos-agents -- curl http://eureka-server:8761/eureka/apps

# Verify DNS resolution
kubectl exec -it <pod-name> -n pos-agents -- nslookup eureka-server
```

### Performance Tuning

1. **JVM Optimization**
```yaml
env:
- name: JAVA_OPTS
  value: "-XX:+UseG1GC -XX:MaxGCPauseMillis=200 -XX:+UseStringDeduplication -XX:MaxRAMPercentage=75.0"
```

2. **Connection Pool Tuning**
```yaml
env:
- name: SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE
  value: "20"
- name: SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE
  value: "5"
```

## Maintenance Windows

### Scheduled Maintenance

1. **Weekly Maintenance (Sundays 2-4 AM)**
   - Update security patches
   - Rotate logs
   - Performance optimization
   - Backup verification

2. **Monthly Maintenance (First Sunday 1-5 AM)**
   - Major version updates
   - Certificate renewal
   - Capacity planning review
   - Disaster recovery testing

### Emergency Procedures

1. **Critical Security Patch**
```bash
# Build patched image
docker build -t pos-agent-framework:security-patch .

# Emergency deployment
kubectl set image deployment/pos-agent-framework pos-agent-framework=pos-agent-framework:security-patch -n pos-agents

# Monitor deployment
kubectl rollout status deployment/pos-agent-framework -n pos-agents
```

2. **Service Outage Response**
```bash
# Scale to zero and back up
kubectl scale deployment pos-agent-framework --replicas=0 -n pos-agents
kubectl scale deployment pos-agent-framework --replicas=3 -n pos-agents

# Check all dependencies
kubectl get pods -n pos-agents
kubectl get services -n pos-agents
```

## Contact Information

- **On-Call Engineer**: [Your contact info]
- **Platform Team**: [Team contact info]
- **Security Team**: [Security contact info]
- **Escalation**: [Management contact info]

## References

- [Kubernetes Documentation](https://kubernetes.io/docs/)
- [Spring Boot Actuator](https://docs.spring.io/spring-boot/docs/current/reference/html/actuator.html)
- [Prometheus Monitoring](https://prometheus.io/docs/)
- [Grafana Dashboards](https://grafana.com/docs/)
