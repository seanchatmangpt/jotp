# JOTP Project Makefile
# Unified entry point for build, test, benchmark, release, and deployment tasks.
#
# Usage:
#   make <target>
#   make test T=MyTest          # run a single test class
#   make it-single T=MyIT       # run a single integration test
#   make benchmark FORKS=2      # override JMH fork count
#   make deploy-release GPG_KEY=<key-id>
#   make gen ARGS="-t core/record -n Person -p com.example"
#   make gen-templates CAT=patterns

# ---------------------------------------------------------------------------
# Variables
# ---------------------------------------------------------------------------

MVND      := mvnd
MVNW      := ./mvnw
# Auto-detect mvnd; fall back to Maven Wrapper
BUILD     := $(shell command -v $(MVND) 2>/dev/null && echo $(MVND) || echo $(MVNW))
THREADS   ?= 1C

# Single-test override: make test T=MyTest
T         ?=

# JMH benchmark tuning
FORKS     ?= 1
WARMUP    ?= 3
ITERS     ?= 5
BENCH_RESULTS := target/benchmark-results.json

# Code generation
ARGS      ?=
CAT       ?=

# Maven Central release
GPG_KEY   ?=

# Cloud deployment
CLOUD     ?= oci

# ---------------------------------------------------------------------------
# Default target
# ---------------------------------------------------------------------------

.DEFAULT_GOAL := help

# ---------------------------------------------------------------------------
# Help
# ---------------------------------------------------------------------------

.PHONY: help
help: ## Show this help message
	@awk 'BEGIN {FS = ":.*##"; printf "\nUsage:\n  make \033[36m<target>\033[0m\n"} \
	  /^[a-zA-Z0-9_-]+:.*?##/ { printf "  \033[36m%-22s\033[0m %s\n", $$1, $$2 } \
	  /^##@/ { printf "\n\033[1m%s\033[0m\n", substr($$0,5) }' $(MAKEFILE_LIST)

# ---------------------------------------------------------------------------
##@ Core Build
# ---------------------------------------------------------------------------

.PHONY: compile
compile: ## Compile sources (parallel, -T$(THREADS))
	$(BUILD) compile -T$(THREADS)

.PHONY: test
test: ## Run unit tests (use T=ClassName for a single class)
ifdef T
	$(BUILD) test -Dtest=$(T)
else
	$(BUILD) test
endif

.PHONY: verify
verify: ## Run all tests + quality checks (unit + integration + spotless)
	$(BUILD) verify

.PHONY: clean
clean: ## Remove build output (target/)
	$(BUILD) clean

.PHONY: format
format: ## Apply Spotless code formatting (Google Java Format / AOSP)
	$(BUILD) spotless:apply -q

.PHONY: format-check
format-check: ## Check formatting without modifying files
	$(BUILD) spotless:check

.PHONY: all
all: ## Full build + guard validation via dx.sh
	./dx.sh all

# ---------------------------------------------------------------------------
##@ mvnd Management
# ---------------------------------------------------------------------------

.PHONY: mvnd-status
mvnd-status: ## Show running Maven Daemon instances
	$(MVND) --status

.PHONY: mvnd-stop
mvnd-stop: ## Stop all Maven Daemon instances
	$(MVND) --stop

.PHONY: mvnd-restart
mvnd-restart: mvnd-stop cache-warm ## Stop daemon and re-warm the build cache

.PHONY: cache-warm
cache-warm: ## Pre-warm classpath cache for faster subsequent builds
	$(BUILD) compile -q -T$(THREADS)

# ---------------------------------------------------------------------------
##@ Benchmarking (JMH)
# ---------------------------------------------------------------------------

.PHONY: benchmark
benchmark: ## Run JMH benchmarks (FORKS=$(FORKS), WARMUP=$(WARMUP), ITERS=$(ITERS))
	$(BUILD) verify -Dbenchmark \
	  -Dexec.args="-f $(FORKS) -wi $(WARMUP) -i $(ITERS) -rff $(BENCH_RESULTS) -rf json"

.PHONY: benchmark-quick
benchmark-quick: ## Quick JMH run: 1 fork, 1 warmup, 2 measurement iterations
	$(BUILD) verify -Dbenchmark \
	  -Dexec.args="-f 1 -wi 1 -i 2 -rff $(BENCH_RESULTS) -rf json"

.PHONY: benchmark-full
benchmark-full: ## Full JMH run: 3 forks, 5 warmup, 10 measurement iterations
	$(BUILD) verify -Dbenchmark \
	  -Dexec.args="-f 3 -wi 5 -i 10 -rff $(BENCH_RESULTS) -rf json"

.PHONY: benchmark-view
benchmark-view: ## Print last benchmark results (target/benchmark-results.json)
	@if [ -f $(BENCH_RESULTS) ]; then \
	  cat $(BENCH_RESULTS); \
	else \
	  echo "No results found at $(BENCH_RESULTS). Run 'make benchmark' first."; \
	fi

# ---------------------------------------------------------------------------
##@ Fat JAR / Distribution
# ---------------------------------------------------------------------------

.PHONY: package
package: ## Package the library JAR (no fat JAR)
	$(BUILD) package -DskipTests

.PHONY: shade
shade: ## Build fat/uber JAR (shade profile) — output: target/jotp-*-shaded.jar
	$(BUILD) package -Dshade -DskipTests

.PHONY: dist-clean
dist-clean: ## Remove the entire target/ directory
	$(BUILD) clean

# ---------------------------------------------------------------------------
##@ Maven Central / Release
# ---------------------------------------------------------------------------

.PHONY: gpg-check
gpg-check: ## Verify GPG key is configured (required for signed releases)
	gpg --list-secret-keys

.PHONY: deploy-snapshot
deploy-snapshot: ## Deploy snapshot to OSSRH (https://s01.oss.sonatype.org)
	$(BUILD) deploy

.PHONY: deploy-release
deploy-release: gpg-check ## Deploy signed release to Maven Central (requires GPG_KEY=<id>)
ifndef GPG_KEY
	$(error GPG_KEY is not set. Usage: make deploy-release GPG_KEY=<your-gpg-key-id>)
endif
	$(BUILD) deploy -Dgpg.keyname=$(GPG_KEY)

.PHONY: release-prepare
release-prepare: ## Run maven-release-plugin prepare phase (tags + bumps version)
	$(BUILD) release:prepare

.PHONY: release-perform
release-perform: ## Run maven-release-plugin perform phase (deploys tagged release)
	$(BUILD) release:perform

# ---------------------------------------------------------------------------
##@ Dogfood (Template Validation)
# ---------------------------------------------------------------------------

.PHONY: dogfood
dogfood: ## Full dogfood pipeline: generate + compile + test + report
	bin/dogfood verify

.PHONY: dogfood-generate
dogfood-generate: ## Check all dogfood source files exist (from templates)
	bin/dogfood generate

.PHONY: dogfood-report
dogfood-report: ## Show template coverage report by category
	bin/dogfood report

.PHONY: dogfood-maven
dogfood-maven: ## Run dogfood via Maven profile (mvnd verify -Ddogfood)
	$(BUILD) verify -Ddogfood

# ---------------------------------------------------------------------------
##@ Code Generation (jgen)
# ---------------------------------------------------------------------------

.PHONY: gen
gen: ## Generate code from a template (ARGS="-t core/record -n Foo -p com.example")
ifndef ARGS
	@echo "Usage: make gen ARGS=\"-t <template> -n <Name> -p <package>\""
	@echo "       make gen-list      # list all templates"
else
	bin/jgen generate $(ARGS)
endif

.PHONY: gen-list
gen-list: ## List all 72 available jgen templates
	bin/jgen list

.PHONY: gen-templates
gen-templates: ## List templates by category (CAT=patterns|core|concurrency|...)
ifdef CAT
	bin/jgen list --category $(CAT)
else
	bin/jgen list
endif

.PHONY: gen-migrate
gen-migrate: ## Detect legacy patterns in source (ARGS="--source ./legacy")
	bin/jgen migrate $(ARGS)

.PHONY: gen-refactor
gen-refactor: ## Full refactor analysis: score + ranked commands (ARGS="--source ./legacy")
	bin/jgen refactor $(ARGS)

# ---------------------------------------------------------------------------
##@ Guard System (Rust)
# ---------------------------------------------------------------------------

.PHONY: guard-build
guard-build: ## Build the Rust dx-guard binary (guard-system/)
	cd guard-system && cargo build --release

.PHONY: guard-check
guard-check: ## Run guard validation against production sources
	./dx.sh validate

.PHONY: guard-clean
guard-clean: ## Clean Rust build artifacts (guard-system/target/)
	cd guard-system && cargo clean

# ---------------------------------------------------------------------------
##@ Cloud Deployment
# ---------------------------------------------------------------------------

.PHONY: deploy
deploy: ## Full deploy pipeline: build JAR → image → infrastructure (CLOUD=$(CLOUD))
	CLOUD_PROVIDER=$(CLOUD) ./deploy.sh

.PHONY: deploy-build
deploy-build: ## Build fat JAR only (no image or infra)
	./deploy.sh --build

.PHONY: deploy-image
deploy-image: ## Build VM image with Packer (requires JAR from deploy-build)
	CLOUD_PROVIDER=$(CLOUD) ./deploy.sh --image

.PHONY: deploy-infra
deploy-infra: ## Provision cloud infrastructure with Terraform
	CLOUD_PROVIDER=$(CLOUD) ./deploy.sh --infra

.PHONY: deploy-destroy
deploy-destroy: ## Tear down cloud infrastructure (irreversible — confirm first)
	@read -p "Destroy $(CLOUD) infrastructure? [y/N] " confirm && \
	  [ "$${confirm}" = "y" ] && CLOUD_PROVIDER=$(CLOUD) ./deploy.sh --destroy || echo "Aborted."

.PHONY: deploy-status
deploy-status: ## Show current deployment status
	CLOUD_PROVIDER=$(CLOUD) ./deploy.sh --status

.PHONY: cloud-config
cloud-config: ## Show active cloud provider configuration
	./deploy.sh --config

# ---------------------------------------------------------------------------
##@ Development Utilities
# ---------------------------------------------------------------------------

.PHONY: repl
repl: ## Launch interactive JShell REPL
	$(BUILD) jshell:run

.PHONY: test-single
test-single: ## Run a single unit test class (T=ClassName required)
ifndef T
	$(error T is not set. Usage: make test-single T=MyTest)
endif
	$(BUILD) test -Dtest=$(T)

.PHONY: it-single
it-single: ## Run a single integration test class (T=ClassName required)
ifndef T
	$(error T is not set. Usage: make it-single T=MyIT)
endif
	$(BUILD) verify -Dit.test=$(T)

.PHONY: deps-tree
deps-tree: ## Print full dependency tree
	$(BUILD) dependency:tree

.PHONY: deps-check
deps-check: ## Check for available dependency version updates
	$(BUILD) versions:display-dependency-updates

.PHONY: setup
setup: ## Run environment setup (JDK 26, mvnd, Maven proxy)
	bash .claude/setup.sh
