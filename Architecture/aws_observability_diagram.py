from diagrams import Diagram, Cluster, Edge
from diagrams.aws.compute import ECS, Fargate
from diagrams.aws.storage import S3
from diagrams.aws.database import ElastiCache, Dynamodb
from diagrams.aws.network import ELB
from diagrams.onprem.monitoring import Prometheus, Grafana
from diagrams.onprem.tracing import Jaeger
from diagrams.onprem.logging import Loki
from diagrams.custom import Custom

# Initialize diagram
with Diagram("AWS Observability Architecture", show=False, filename="aws_observability_diagram"):
    
    # External Load Balancer
    lb = ELB("Application Load Balancer")
    
    # Application Services
    with Cluster("Application Services (ECS Fargate)"):
        services = [
            Fargate("Service 1"),
            Fargate("Service 2"),
            Fargate("Service 3")
        ]
    
    # OpenTelemetry Collector
    with Cluster("OpenTelemetry Collector Cluster"):
        otel_collectors = [
            Custom("Collector\n(Receivers)", "./icons/otel_collector_receiver.png"),
            Custom("Collector\n(Receivers)", "./icons/otel_collector_receiver.png")
        ]
    
    # Observability Stack
    with Cluster("Observability Stack (ECS Fargate)"):
        with Cluster("Tracing"):
            jaeger = Jaeger("Jaeger")
        
        with Cluster("Metrics"):
            prometheus = Prometheus("Prometheus")
        
        with Cluster("Logging"):
            loki = Loki("Loki")
        
        with Cluster("Visualization"):
            grafana = Grafana("Grafana")
    
    # Storage Solutions
    with Cluster("Storage Layer"):
        metrics_db = Dynamodb("Metrics Storage")
        logs_storage = S3("Logs Storage")
        traces_cache = ElastiCache("Traces Storage")
    
    # Connections
    for service in services:
        service >> Edge(color="darkgreen") >> otel_collectors
    
    for collector in otel_collectors:
        collector >> Edge(color="blue") >> jaeger
        collector >> Edge(color="orange") >> prometheus
        collector >> Edge(color="green") >> loki
    
    jaeger >> traces_cache
    prometheus >> metrics_db
    loki >> logs_storage
    
    jaeger >> Edge(color="darkblue") >> grafana
    prometheus >> Edge(color="darkblue") >> grafana
    loki >> Edge(color="darkblue") >> grafana
    
    lb >> grafana
