from diagrams import Diagram, Cluster, Edge
from diagrams.aws.compute import ECS, Fargate
from diagrams.aws.storage import S3
from diagrams.aws.database import ElastiCache, Dynamodb
from diagrams.aws.network import ELB
from diagrams.onprem.monitoring import Prometheus, Grafana
from diagrams.onprem.logging import Fluentd
from diagrams.onprem.queue import Kafka
from diagrams.onprem.tracing import Jaeger
from diagrams.custom import Custom

with Diagram("AWS Observability Architecture with OpenTelemetry", show=False, filename="observability-architecture"):
    
    # Load Balancer for external access
    lb = ELB("Application Load Balancer")
    
    # Application Services (simplified)
    with Cluster("Application Services (ECS Fargate)"):
        app_services = [
            Fargate("Service 1"),
            Fargate("Service 2"),
            Fargate("Service 3")
        ]
    
    # OpenTelemetry Collector Gateway
    with Cluster("OpenTelemetry Collector (ECS Fargate)"):
        otel_collector = Custom("OpenTelemetry\nCollector Gateway", "./otel-icon.png")
    
    # Observability Stack
    with Cluster("Observability Stack (ECS Fargate)"):
        # Tracing
        jaeger = Jaeger("Jaeger")
        
        # Metrics
        prometheus = Prometheus("Prometheus")
        
        # Logs
        loki = Custom("Loki", "./loki-icon.png")
        
        # Visualization
        grafana = Grafana("Grafana")
    
    # Storage Solutions
    with Cluster("Storage"):
        # For Prometheus metrics
        prometheus_storage = Dynamodb("Prometheus\nMetrics Storage")
        
        # For Loki logs
        loki_storage = S3("Loki Log Storage")
        
        # For Jaeger traces
        jaeger_storage = ElastiCache("Jaeger\nTrace Storage")
    
    # Connections
    for service in app_services:
        service >> Edge(color="darkgreen", style="dashed", label="telemetry data") >> otel_collector
    
    lb >> Edge(label="queries") >> grafana
    
    otel_collector >> Edge(label="traces") >> jaeger
    otel_collector >> Edge(label="metrics") >> prometheus
    otel_collector >> Edge(label="logs") >> loki
    
    jaeger >> jaeger_storage
    prometheus >> prometheus_storage
    loki >> loki_storage
    
    jaeger >> Edge(color="blue") >> grafana
    prometheus >> Edge(color="blue") >> grafana
    loki >> Edge(color="blue") >> grafana
