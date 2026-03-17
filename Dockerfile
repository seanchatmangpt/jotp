# Multi-stage Dockerfile for JOTP Java 26 Project
# Stage 1: Build
FROM --platform=linux/amd64 local/jotp-java26-base AS builder

# Install build dependencies
RUN apk add --no-cache \
    tar \
    wget \
    bash

# Set working directory
WORKDIR /build

# Copy Maven wrapper and project files
COPY mvnw pom.xml ./
COPY .mvn .mvn
COPY src ./src
COPY module-info.java ./

# Make mvnw executable
RUN chmod +x mvnw

# Build the project (skip tests for build stage, run in test stage)
RUN ./mvnw clean package -DskipTests -B -q

# Stage 2: Runtime
FROM --platform=linux/amd64 local/jotp-java26-base AS runtime

# Install runtime dependencies
RUN apk add --no-cache \
    bash \
    curl

# Create application user
RUN addgroup -S jotp && adduser -S jotp -G jotp

# Set working directory
WORKDIR /app

# Copy built artifacts from builder stage
COPY --from=builder /build/target/*.jar /app/jotp.jar

# Change ownership
RUN chown -R jotp:jotp /app

# Switch to non-root user
USER jotp

# Expose port (if running as server)
EXPOSE 8080

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
    CMD curl -f http://localhost:8080/actuator/health || exit 1

# Default command (can be overridden)
ENTRYPOINT ["java", \
    "-XX:+UseContainerSupport", \
    "-XX:MaxRAMPercentage=75.0", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-jar", \
    "/app/jotp.jar"]

# Stage 3: Test (separate stage for running tests)
FROM --platform=linux/amd64 local/jotp-java26-base AS test

# Set working directory
WORKDIR /build

# Copy test resources
COPY --from=builder /build/target/*.jar /build/

# Run tests
RUN ./mvnw test -B

# Stage 4: Development (with debugging support)
FROM --platform=linux/amd64 local/jotp-java26-base AS development

# Install development tools
RUN apk add --no-cache \
    tar \
    wget \
    bash \
    git \
    curl

# Set working directory
WORKDIR /workspace

# Copy project files
COPY . .

# Make mvnw executable
RUN chmod +x mvnw

# Expose debug port
EXPOSE 5005

# Default command for development
ENTRYPOINT ["tail", "-f", "/dev/null"]
