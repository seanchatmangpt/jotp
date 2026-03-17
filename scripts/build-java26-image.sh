#!/bin/bash
# Build script for Java 26 base image

set -euo pipefail

IMAGE_NAME="jotp-java26-base"
IMAGE_TAG="latest"

echo "🚀 Building Java 26 base image..."

# Create Dockerfile for Java 26
cat > Dockerfile.java26 << 'EOF'
# Build stage
FROM ubuntu:24.04 AS builder

# Install dependencies
RUN apt-get update && apt-get install -y --no-install-recommends \
    curl \
    tar \
    gzip \
    ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# Install Java 26 EA
ENV JAVA_HOME=/opt/java
ENV JDK_VERSION=26
ENV JDK_BUILD=33

RUN mkdir -p /opt/java \
    && curl -fsSL "https://download.java.net/java/early_access/jdk${JDK_VERSION}/${JDK_BUILD}/GPL/openjdk-${JDK_VERSION}-ea+${JDK_BUILD}_linux-x64_bin.tar.gz" \
    | tar -xzf - -C /opt/java --strip-components=1

# Install Maven Daemon
ARG MVND_VERSION=2.0.0-rc-3
ENV MVND_HOME=/opt/mvnd
ENV MAVEN_HOME=/opt/maven
ENV PATH=/opt/java/bin:/opt/mvnd/bin:/opt/maven/bin:${PATH}

RUN mkdir -p /opt/mvnd \
    && curl -fsSL "https://github.com/apache/maven-mvnd/releases/download/${MVND_VERSION}/maven-mvnd-${MVND_VERSION}-linux-amd64.tar.gz" \
    | tar -xzf - -C /opt/mvnd --strip-components=1 \
    && ln -s /opt/mvnd/bin/mvnd /usr/local/bin/mvnd \
    && ln -s /opt/mvnd/bin/mvnd /usr/local/bin/mvn \
    && ln -s /opt/mvnd/bin/mvnw /usr/local/bin/mvnw

# Runtime stage
FROM ubuntu:24.04 AS runtime

# Install runtime dependencies
RUN apt-get update && apt-get install -y --no-install-recommends \
    curl \
    && rm -rf /var/lib/apt/lists/*

# Copy Java and Maven from builder
COPY --from=builder /opt/java /opt/java
COPY --from=builder /opt/mvnd /opt/mvnd
# Maven is included in mvnd, no separate Maven needed

# Create symlinks
RUN ln -s /opt/java/bin/java /usr/local/bin/java \
    && ln -s /opt/java/bin/javac /usr/local/bin/javac \
    && ln -s /opt/java/bin/jshell /usr/local/bin/jshell \
    && ln -s /opt/mvnd/bin/mvnd /usr/local/bin/mvnd \
    && ln -s /opt/mvnd/bin/mvnd /usr/local/bin/mvn \
    && ln -s /opt/mvnd/bin/mvnw /usr/local/bin/mvnw

# Environment variables
ENV JAVA_HOME=/opt/java
ENV MVND_HOME=/opt/mvnd
ENV MAVEN_HOME=/opt/maven
ENV PATH=/opt/java/bin:/opt/mvnd/bin:/opt/maven/bin:${PATH}

# Create app directory
WORKDIR /app

# Verify installations
RUN java --version && mvnd --version

# Default command
CMD ["bash"]
EOF

echo "📦 Building Docker image..."
docker build -f Dockerfile.java26 -t ${IMAGE_NAME}:${IMAGE_TAG} .

echo "✅ Build complete! Image: ${IMAGE_NAME}:${IMAGE_TAG}"
echo "🧪 Testing the image..."
docker run --rm ${IMAGE_NAME}:${IMAGE_TAG} java -version
docker run --rm ${IMAGE_NAME}:${IMAGE_TAG} mvnd --version

# Cleanup
rm -f Dockerfile.java26

echo "🎉 All done!"