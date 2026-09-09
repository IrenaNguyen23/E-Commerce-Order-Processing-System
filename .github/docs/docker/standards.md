# Docker Rules

Each service must contain:

Dockerfile

Multi-stage build.

Base Image:

eclipse-temurin:21-jdk

Expose:

8080

Healthcheck enabled.

Container name format:

commerceflow-{service-name}

Examples:

commerceflow-auth
commerceflow-order
commerceflow-payment