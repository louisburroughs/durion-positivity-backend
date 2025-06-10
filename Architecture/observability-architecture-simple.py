from diagrams import Diagram, Cluster, Edge
from diagrams.aws.compute import ECS, Fargate
from diagrams.aws.storage import S3
from diagrams.aws.database import ElastiCache, Dynamodb
from diagrams.aws.network import ELB
from diagrams.onprem.monitoring import Prometheus, Grafana
from diagrams.onprem.tracing import Jaeger

with Diagram("AWS Observability Architecture", show=False, filename="observability-architecture"):
    
    # Load Balancer for external access
    lb = ELB("Application Load Balancer")
    
    # Application Services
    with Cluster("Application Services (ECS Fargate)"):
        app_services = [
            Fargate("Microservice 1"),
            Fargate("Microservice 2")
        ]
    
    # OpenTelemetry Collector
    with Cluster("OpenTelemetry Collector"):
        otel = Fargate("OpenTelemetry\nCollector Gateway")
    
    # Observability Stack
    with Cluster("Observability Stack"):
        jaeger = Jaeger("Jaeger")
        prometheus = Prometheus("Prometheus")
        loki = Fargate("Loki")
        grafana = Grafana("Grafana")
    
    # Storage Solutions
    with Cluster("Storage"):
        metrics_store = Dynamodb("Metrics Storage")
        logs_store = S3("Logs Storage")
        traces_store = ElastiCache("Traces Storage")
    
    # Connections
    for service in app_services:
        service >> otel
    
    otel >> jaeger
    otel >> prometheus
    otel >> loki
    
    jaeger >> traces_store
    prometheus >> metrics_store
    loki >> logs_store
    
    jaeger >> grafana
    prometheus >> grafana
    loki >> grafana
    
    lb >> grafana
