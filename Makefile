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
# Local runtime  (Tier L)   — implemented in Phase 4 and Phase 7
# ---------------------------------------------------------------------------
.PHONY: up
up: ## Start the local stack with docker compose
	@echo "Not yet implemented — Phase 4." && exit 1

.PHONY: down
down: ## Stop the local stack
	@echo "Not yet implemented — Phase 4." && exit 1

.PHONY: kind-up
kind-up: ## Create the local kind cluster and bootstrap ArgoCD
	@echo "Not yet implemented — Phase 7." && exit 1

.PHONY: kind-down
kind-down: ## Delete the local kind cluster
	@echo "Not yet implemented — Phase 7." && exit 1

# ---------------------------------------------------------------------------
# Infrastructure validation  (safe — never touches AWS)
# ---------------------------------------------------------------------------
.PHONY: tf-validate
tf-validate: ## terraform fmt, validate, tflint and checkov across all roots
	@echo "Not yet implemented — Phase 8." && exit 1

.PHONY: tf-test
tf-test: ## terraform test with mocked providers (Tier P assertions)
	@echo "Not yet implemented — Phase 9." && exit 1

# ---------------------------------------------------------------------------
# Sandbox  (Tier S) — these APPLY to real AWS. A human runs them.
# ---------------------------------------------------------------------------
.PHONY: sandbox-up
sandbox-up: ## [APPLIES TO AWS] Provision the 180-minute sandbox end to end
	@echo "Not yet implemented — Phase 8." && exit 1

.PHONY: sandbox-status
sandbox-status: ## Elapsed session time and cluster health
	@echo "Not yet implemented — Phase 8." && exit 1

.PHONY: sandbox-down
sandbox-down: ## [APPLIES TO AWS] Destroy everything in the sandbox
	@echo "Not yet implemented — Phase 8." && exit 1

# ---------------------------------------------------------------------------
# Docs
# ---------------------------------------------------------------------------
.PHONY: diagrams
diagrams: ## Render every Mermaid diagram to SVG, failing on a parse error
	@command -v mmdc >/dev/null || { echo "install: npm i -g @mermaid-js/mermaid-cli"; exit 1; }
	@for f in docs/diagrams/*.mmd; do \
	  printf '%-30s ' "$$(basename $$f)"; \
	  mmdc -i "$$f" -o "$${f%.mmd}.svg" >/dev/null 2>&1 && echo OK || { echo FAIL; exit 1; }; \
	done
