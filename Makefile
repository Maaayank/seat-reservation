# Convenience targets. Everything also works without make; see README.md.
URL ?= http://localhost:8080
KEY ?= local-admin-key
PROFILE ?= smoke
COMPOSE ?= docker compose

.PHONY: up down test burst burst-full burst-in-network

up:            ## Start app, Postgres, Prometheus, Grafana
	$(COMPOSE) up -d --build

down:
	$(COMPOSE) down

test:          ## Unit + integration + concurrency tests (needs Docker/Podman for Testcontainers)
	./mvnw -B verify

burst:         ## make burst URL=https://... KEY=... [PROFILE=smoke|full]
	./burst.sh $(URL) $(KEY) --profile $(PROFILE)

burst-full:
	./burst.sh $(URL) $(KEY) --profile full

# Full-scale local run from inside the compose network. Avoids the host port
# forwarder, which can drop connections at thousands of concurrent requests
# (seen with Podman on Windows).
burst-in-network:
	docker build -q -f burst/Dockerfile -t seat-burst .
	docker run --rm --network $$(docker network ls --format '{{.Name}}' | grep _default | head -1) \
		seat-burst http://app:8080 $(KEY) --profile full
