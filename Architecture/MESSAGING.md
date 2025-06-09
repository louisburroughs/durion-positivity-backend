## Amazon SNS (Simple Notification Service)

SNS is a fully managed pub/sub messaging service that enables you to decouple microservices, distributed systems, and serverless applications.

Key features in your architecture:
• Acts as a central message broker for your microservices
• Enables event-driven communication between your Spring Boot modules
• Supports multiple subscription protocols (HTTP/S, email, SMS, SQS, Lambda)
• Provides fan-out capability (one message to many recipients)

How it's used in your system:
• Services like order, invoice, and events publish notifications when state changes occur
• Multiple interested services can subscribe to relevant topics
• For example, when a new order is created, the order service publishes to an "order-created" topic, which might trigger workflows in inventory, accounting, and customer services

## Amazon SQS (Simple Queue Service)

SQS is a fully managed message queuing service that enables you to decouple and scale microservices, distributed systems, and serverless applications.

Key features in your architecture:
• Provides asynchronous processing capabilities
• Ensures reliable message delivery with at-least-once delivery guarantee
• Supports dead-letter queues for handling failed message processing
• Offers FIFO (First-In-First-Out) queues for guaranteed ordering when needed

How it's used in your system:
• Receives messages from SNS topics
• Buffers messages for services like event-receiver
• Enables load leveling during traffic spikes
• Provides fault tolerance if a service is temporarily unavailable

## SNS-SQS Integration in Your Architecture

The diagram shows SNS connected to SQS, which is a common pattern called the "fanout" pattern:

1. A microservice publishes an event to an SNS topic
2. SNS distributes this message to multiple SQS queues that are subscribed to the topic
3. Each interested microservice consumes messages from its own dedicated queue
4. This pattern provides:
   • Decoupling between publishers and subscribers
   • Buffering capability during traffic spikes
   • Resilience if a service goes down temporarily

For example, when your vehicle-inventory service updates inventory levels, it could publish to an SNS topic. This message would be delivered to SQS queues for the catalog service, shop-manager service, and
potentially others that need to react to inventory changes.

This event-driven architecture allows your microservices to communicate asynchronously, improving overall system resilience and scalability.