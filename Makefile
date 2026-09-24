.DEFAULT_GOAL := help
SHELL := /bin/bash

GRADLE := ./gradlew

# ---------------------------------------------------------------------------
# Help
# ---------------------------------------------------------------------------
.PHONY: help
help: ## Show this help
	@grep -hE '^[a-zA-Z_.-]+:.*?## ' $(MAKEFILE_LIST) \
	  | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-22s\033[0m %s\n", $$1, $$2}'

# ---------------------------------------------------------------------------
# Build and quality  (Tier L)
# ---------------------------------------------------------------------------
.PHONY: format
format: ## Apply Spotless formatting
	$(GRADLE) spotlessApply

.PHONY: build
build: ## Compile, lint, unit test and enforce the coverage gate
	$(GRADLE) build

.PHONY: test
test: ## Unit tests only
	$(GRADLE) test

.PHONY: integration-test
integration-test: ## Testcontainers suites (requires a running Docker daemon)
	$(GRADLE) integrationTest

.PHONY: check
check: format build ## Format then run every quality gate

.PHONY: clean
clean: ## Remove build output
	$(GRADLE) clean

# ---------------------------------------------------------------------------
# Container image  (Tier L build, promoted unchanged to S and P)  — ADR-0010
# ---------------------------------------------------------------------------
SERVICE   ?= gateway
IMAGE_TAG ?= dev
IMAGE     := starling/$(SERVICE):$(IMAGE_TAG)

.PHONY: image
image: ## Build the jlink+CDS distroless image for SERVICE=<name>
	$(GRADLE) :services:$(SERVICE):bootJar
	docker build -f docker/Dockerfile \
	  --build-arg JAR_FILE=services/$(SERVICE)/build/libs/$(SERVICE).jar \
	  --build-arg SERVICE=$(SERVICE) \
	  --build-arg GIT_SHA=$$(git rev-parse --short HEAD 2>/dev/null || echo dev) \
	  --build-arg BUILD_TIME=$$(date -u +%Y-%m-%dT%H:%M:%SZ) \
	  -t $(IMAGE) .

WEB_IMAGE := starling/web:$(IMAGE_TAG)

.PHONY: image-web
image-web: ## Build the distroless Node image for the Next.js frontend
	docker build -f docker/Dockerfile.web \
	  --build-arg GIT_SHA=$$(git rev-parse --short HEAD 2>/dev/null || echo dev) \
	  --build-arg BUILD_TIME=$$(date -u +%Y-%m-%dT%H:%M:%SZ) \
	  -t $(WEB_IMAGE) .

.PHONY: image-verify
image-verify: ## Smoke-test the BUILT IMAGE, not the Gradle classpath
	./scripts/image-verify.sh $(IMAGE)
.PHONY: image-report
image-report: ## Size report and gates; BASELINE=<image> enables the warm-pull gate
	./scripts/image-report.sh $(IMAGE) $(BASELINE)

.PHONY: modules-derive
modules-derive: ## Re-derive the jlink module set and diff it against the allow-list
	$(GRADLE) :services:$(SERVICE):bootJar
	./scripts/derive-jlink-modules.sh $(SERVICE)

.PHONY: image-all
image-all: image image-verify image-report ## Build, verify and measure in one go

# ---------------------------------------------------------------------------
# Local runtime  (Tier L)   — implemented in Phase 4 and Phase 7
# ---------------------------------------------------------------------------
.PHONY: up
up: ## Start the local stack with docker compose
	docker compose up -d --wait
	@echo
	@echo "  DynamoDB / S3   http://localhost:4566   (LocalStack, tables bootstrapped)"
	@echo "  redis-main      localhost:6379          (normal users, rate limits)"
	@echo "  redis-celeb     localhost:6380          (celebrity profiles and fragments)"
	@echo "  postgres        localhost:5432          (search index only)"

.PHONY: down
down: ## Stop the local stack, keeping data volumes
	docker compose --profile app down --remove-orphans

.PHONY: up-app
up-app: ## Start the backing services AND the six application containers
	@# The images package an already-built jar rather than compiling one, so this has to run
	@# first; Compose would otherwise fail on a missing JAR_FILE after pulling every base.
	$(GRADLE) bootJar
	docker compose --profile app up -d --build --wait
	@# --wait returns when Compose's own conditions are met, which for these six is only
	@# "the process started": they are distroless and cannot carry a healthcheck. See the
	@# comment above the app profile in compose.yaml.
	./tools/wait-for-stack.sh
	@echo
	@echo "  web             http://localhost:3000"
	@echo "  gateway         http://localhost:8080"

.PHONY: logs-app
logs-app: ## Tail the application containers
	docker compose --profile app logs -f --tail 100 \
	  gateway user-service tweet-service timeline-service fanout-worker web

.PHONY: wait
wait: ## Block until the application stack answers
	./tools/wait-for-stack.sh

.PHONY: e2e
e2e: ## Run the Playwright suite against the running stack (ARGS=... passes flags through)
	@# Deliberately depends on nothing. The suite asserts against a stack built from the
	@# images, and making this target build one would hide the case that matters: an image
	@# that is stale relative to the source. Run `make up-app` first, on purpose.
	./tools/e2e.sh $(ARGS)

.PHONY: e2e-full
e2e-full: up-app e2e ## Build, start and then exercise the whole product end to end

.PHONY: down-hard
down-hard: ## Stop the local stack and delete its volumes
	@# The supported way back to empty tables. DynamoDB cannot alter a key schema in place,
	@# so changing one in tools/dynamodb-tables.json requires this rather than a restart.
	docker compose --profile app down --remove-orphans --volumes

.PHONY: tables
tables: ## Re-run the DynamoDB table bootstrap against the running stack
	docker compose exec -T localstack python3 /opt/starling-tools/localstack/create-tables.py

.PHONY: kind-up
kind-up: ## Create the local kind cluster (Calico + ArgoCD) — CNI=kindnet to skip Calico
	./scripts/kind-up.sh

.PHONY: kind-deploy
kind-deploy: ## Build, load and helm-install every chart onto the kind cluster
	./scripts/kind-deploy.sh

.PHONY: kind-down
kind-down: ## Delete the local kind cluster
	kind delete cluster --name $${CLUSTER:-starling}

.PHONY: helm-lint
helm-lint: ## Lint and render every chart for every environment, then schema-check it
	./scripts/helm-validate.sh

# ---------------------------------------------------------------------------
# Infrastructure validation  (safe — never touches AWS)
# ---------------------------------------------------------------------------
.PHONY: tf-validate
tf-validate: ## terraform fmt, validate, tflint and checkov across all roots
	./scripts/tf-validate.sh

.PHONY: tf-test
tf-test: ## terraform test with mocked providers (Tier P assertions)
	./scripts/tf-test.sh

# ---------------------------------------------------------------------------
# Sandbox  (Tier S) — these APPLY to real AWS. A human runs them.
# ---------------------------------------------------------------------------
.PHONY: probe
probe: ## [TOUCHES AWS] Measure what the playground actually permits (Phase 6)
	@# Creates and immediately destroys a handful of tiny resources, all named
	@# probe-<epoch>. Non-fatal throughout: a denial is the result, not an error.
	./scripts/probe.sh $(ARGS)

.PHONY: probe-selftest
probe-selftest: ## Test the probe against a stubbed AWS CLI — no credentials needed
	@# The probe runs once per 180-minute session against an account nobody can
	@# reproduce. Debugging it there costs the session, so it is tested here.
	./scripts/probe-selftest.sh

.PHONY: sandbox-up
sandbox-up: ## [APPLIES TO AWS] Provision the 180-minute sandbox end to end
	@scripts/sandbox-up.sh $(ARGS)

.PHONY: sandbox-plan
sandbox-plan: ## Dry-run the provisioning path — prints every command, touches nothing
	@scripts/sandbox-up.sh --dry-run

.PHONY: sandbox-status
sandbox-status: ## Elapsed session time against the 180-minute budget, plus cluster health
	@scripts/sandbox-status.sh

.PHONY: sandbox-down
sandbox-down: ## [APPLIES TO AWS] Destroy everything, then ask AWS directly what survived
	@scripts/sandbox-down.sh $(ARGS)

.PHONY: sandbox-selftest
sandbox-selftest: ## Test the whole lifecycle against stub AWS/terraform/kubectl — no account needed
	@scripts/sandbox-selftest.sh

# ---------------------------------------------------------------------------
# Docs
# ---------------------------------------------------------------------------
.PHONY: diagrams
diagrams: ## Render every Mermaid diagram to SVG, failing on a parse error
	@echo '{"args":["--no-sandbox","--disable-setuid-sandbox"]}' > /tmp/pptr.json; \
	MMDC=$$(command -v mmdc || echo "npx --yes @mermaid-js/mermaid-cli"); \
	for f in docs/diagrams/*.mmd; do \
	  printf '%-30s ' "$$(basename $$f)"; \
	  $$MMDC -p /tmp/pptr.json -i "$$f" -o "$${f%.mmd}.svg" >/tmp/mmd.log 2>&1 \
	    && echo OK || { echo FAIL; tail -20 /tmp/mmd.log; exit 1; }; \
	done

.PHONY: load-smoke
load-smoke: ## One pass through the product flow, in-cluster — proves the k6 harness still works
	@./scripts/load-test.sh smoke

.PHONY: load-test
load-test: ## Ramped arrival-rate load test that drives the HPA; PEAK_RPS=<n> DURATION=<t>
	@./scripts/load-test.sh ramp $${PEAK_RPS:-20} $${DURATION:-2m}

.PHONY: rollback-drill
rollback-drill: ## Ship a deliberately broken canary and assert Argo Rollouts rejects it
	@./scripts/rollback-drill.sh

.PHONY: rollback-drill-control
rollback-drill-control: ## Control run — the same canary machinery must PROMOTE a healthy build
	@./scripts/rollback-drill.sh --healthy

.PHONY: aiops-selftest
aiops-selftest: ## Offline two-sided test of the risk analyser — no cluster, AWS, model or network
	@./scripts/aiops-selftest.sh

.PHONY: risk-comment
risk-comment: ## Plan the prod root against LocalStack and render the Terraform risk comment
	@docker rm -f tfmock >/dev/null 2>&1 || true
	@docker run -d --name tfmock -p 4566:4566 -e SERVICES=sts,iam,ec2 \
		localstack/localstack:3.8 >/dev/null
	@until curl -sf http://localhost:4566/_localstack/health >/dev/null; do sleep 2; done
	@./scripts/tf-plan-mock.sh infra/terraform/envs/prod /tmp/prod-plan.json
	@python3 tools/aiops/risk_comment.py --plan /tmp/prod-plan.json \
		--title 'Tier P (production root, never applied)'
	@docker rm -f tfmock >/dev/null 2>&1 || true

.PHONY: triage
triage: ## Draft a probable cause for a firing alert; ALERT=<path to Alertmanager payload>
	@python3 tools/aiops/triage.py --alert $${ALERT:-tools/aiops/fixtures/alert.json}

.PHONY: gap-verify
gap-verify: ## Resolve every citation and artefact the gap register names; fail on a dead one
	@./scripts/gap-verify.sh
