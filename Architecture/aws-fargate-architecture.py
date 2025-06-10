from diagrams import Diagram, Cluster, Edge
from diagrams.aws.compute import Fargate
from diagrams.aws.database import Dynamodb, ElastiCache
from diagrams.aws.network import APIGateway, ELB
from diagrams.aws.security import WAF
from diagrams.aws.network import CloudFront
from diagrams.aws.storage import S3
from diagrams.aws.integration import SNS, SQS
from diagrams.aws.management import Cloudwatch
from diagrams.aws.security import IAM
from diagrams.aws.network import VPC, PrivateSubnet, PublicSubnet
from diagrams.aws.general import Users
from diagrams.onprem.client import Client

# Define the microservices
microservices = [
    "pos-main", "catalog", "customer", "invoice", "work-order", 
    "price", "shop-manager", "inquiry", "order", "accounting", 
    "events", "event-receiver", "image", "vehicle-inventory", 
    "inventory", "security-service", "people", "location", 
    "service-discovery"
]

# Define the microservices that use ElastiCache
elasticache_services = [
    "vehicle-fitment", "vehicle-reference-nhtsa", "vehicle-reference-carapi"
]

# Combine all services
all_services = microservices + elasticache_services

with Diagram("AWS Fargate Microservices Architecture", show=False, filename="aws-fargate-architecture", outformat="png"):
    
    users = Users("End Users")
    
    with Cluster("AWS Cloud"):
        # Frontend
        with Cluster("Frontend"):
            cloudfront = CloudFront("CloudFront")
            s3 = S3("S3 Static Website")
            frontend = Fargate("Vue.js Frontend")
            
            users >> cloudfront >> s3
            users >> cloudfront >> frontend
        
        # API Gateway with WAF
        api_gateway = APIGateway("API Gateway")
        waf = WAF("WAF")
        
        frontend >> api_gateway
        waf - Edge(style="dotted") - api_gateway
        
        with Cluster("VPC"):
            with Cluster("ECS Cluster"):
                with Cluster("Auto Scaling"):
                    # Dynamodb microservices
                    with Cluster("Fargate Services (DynamoDB)"):
                        dynamo_services = []
                        for service in microservices:
                            dynamo_services.append(Fargate(service))
                    
                    # ElastiCache microservices
                    with Cluster("Fargate Services (ElastiCache)"):
                        elastic_services = []
                        for service in elasticache_services:
                            elastic_services.append(Fargate(service))
            
            # Databases
            with Cluster("Data Layer"):
                dynamodb = Dynamodb("DynamoDB Tables")
                elasticache = ElastiCache("ElastiCache Redis")
            
            # Event infrastructure
            with Cluster("Event Infrastructure"):
                sns = SNS("SNS")
                sqs = SQS("SQS")
                events = Cloudwatch("CloudWatch Events")
            
            # Security
            iam = IAM("IAM Roles")
        
        # Connect API Gateway to all services
        for service in dynamo_services + elastic_services:
            api_gateway >> service
        
        # Connect services to their respective databases
        for service in dynamo_services:
            service >> dynamodb
        
        for service in elastic_services:
            service >> elasticache
        
        # Connect event services
        for service in dynamo_services + elastic_services:
            service >> sns
        
        sns >> sqs
        sqs >> [s for s in dynamo_services + elastic_services if "event" in s.label.lower()]
