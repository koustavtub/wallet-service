# =============================================================================
# Multi-stage Dockerfile
# Stage 1: Build the fat JAR with Gradle
# Stage 2: Run on a minimal JRE image
# =============================================================================

# ── Stage 1: Build ────────────────────────────────────────────────────────────
FROM gradle:9.5-jdk21 AS builder

WORKDIR /app

# Copy build files first and resolve dependencies — cached unless they change
COPY build.gradle settings.gradle ./
RUN gradle dependencies --no-daemon -q

# Copy source and build — skip tests (run separately via `make test`)
COPY src ./src
RUN gradle bootJar --no-daemon -x test -q

# ── Stage 2: Runtime ──────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Non-root user for security
RUN addgroup -S keychain && adduser -S keychain -G keychain
USER keychain

COPY --from=builder /app/build/libs/keychain-wallet-*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
