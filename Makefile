# =============================================================================
# Keychain Wallet Service — Makefile
# =============================================================================
.PHONY: help build run test test-unit test-int \
        db-up db-down up down logs clean stub

# ── Config ────────────────────────────────────────────────────────────────────
BASE_URL   ?= http://localhost:8080
GRADLE     := ./gradlew
DB_COMPOSE := docker-compose.dev.yml

# ── Default target ────────────────────────────────────────────────────────────
help:
	@echo ""
	@echo "  Keychain Wallet Service"
	@echo ""
	@echo "  Development"
	@echo "  ──────────────────────────────────────"
	@echo "  make db-up          Start Postgres only (for native app dev)"
	@echo "  make db-down        Stop Postgres"
	@echo "  make run            Run app locally (requires db-up first)"
	@echo ""
	@echo "  Build & Test"
	@echo "  ──────────────────────────────────────"
	@echo "  make build          Compile and package (skip tests)"
	@echo "  make test           Run all tests (unit + integration)"
	@echo "  make test-unit      Run unit tests only (no Docker)"
	@echo "  make test-int       Run integration tests only (needs Docker)"
	@echo ""
	@echo "  Docker (full stack)"
	@echo "  ──────────────────────────────────────"
	@echo "  make up             Build image and start all services"
	@echo "  make down           Stop all services"
	@echo "  make logs           Tail wallet-service logs"
	@echo ""
	@echo "  Demo"
	@echo "  ──────────────────────────────────────"
	@echo "  make stub           Run the Order Service stub against BASE_URL"
	@echo "  make clean          Remove build artifacts"
	@echo ""

# ── Development ───────────────────────────────────────────────────────────────

## Start Postgres only (use this when running the app from your IDE or `make run`)
db-up:
	docker compose -f $(DB_COMPOSE) up -d
	@echo "⏳ Waiting for Postgres to be healthy..."
	@docker compose -f $(DB_COMPOSE) exec postgres \
		sh -c 'until pg_isready -U keychain -d keychain; do sleep 1; done'
	@echo "✅ Postgres is ready."

## Stop Postgres
db-down:
	docker compose -f $(DB_COMPOSE) down

## Run the Spring Boot app natively (db-up must be run first)
run:
	DB_URL=jdbc:postgresql://localhost:5432/keychain \
	DB_USER=keychain \
	DB_PASS=keychain \
	$(GRADLE) bootRun

# ── Build ─────────────────────────────────────────────────────────────────────

## Compile and package the fat JAR (tests skipped)
build:
	$(GRADLE) bootJar -x test

# ── Tests ─────────────────────────────────────────────────────────────────────

## Run all tests — unit tests run without Docker; integration tests spin up
## a Postgres container automatically via Testcontainers (Docker required).
test:
	$(GRADLE) test

## Unit tests only — no Docker required, very fast
test-unit:
	$(GRADLE) unitTest

## Integration tests only — requires Docker for Testcontainers
test-int:
	$(GRADLE) integrationTest

# ── Docker full stack ─────────────────────────────────────────────────────────

## Build the Docker image and start Postgres + Wallet Service
up:
	docker compose up --build -d
	@echo "⏳ Waiting for wallet service to be ready..."
	@sleep 5
	@curl -sf $(BASE_URL)/actuator/health 2>/dev/null && echo "✅ Service is up." \
		|| echo "ℹ️  Service may still be starting — run 'make logs' to check."

## Stop and remove all containers
down:
	docker compose down

## Tail wallet-service logs
logs:
	docker compose logs -f wallet-service

# ── Demo ──────────────────────────────────────────────────────────────────────

## Run the Order Service stub to demonstrate the full integration
stub:
	@chmod +x stub/order_service_stub.sh
	@stub/order_service_stub.sh $(BASE_URL)

# ── Clean ─────────────────────────────────────────────────────────────────────
clean:
	$(GRADLE) clean
	docker compose down -v 2>/dev/null || true
	docker compose -f $(DB_COMPOSE) down -v 2>/dev/null || true
