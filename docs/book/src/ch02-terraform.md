# Chapter 2: Use Terraform to Create GitHub Issues and Milestones

You have a working board process. Now you need a project. Specifically, you need a GitHub repository for TaskFlow, a set of milestones tracking the development stages, and a set of issues tracking the work items. You could create all of this by clicking through the GitHub UI. You should not.

The GitHub UI creates configuration that lives in GitHub's database and nowhere else. You cannot review it in a pull request. You cannot reproduce it from scratch after an accidental deletion. You cannot diff it between environments. You cannot test that it is correct before applying it.

Terraform solves this. Infrastructure as code is not just for AWS resources. GitHub issues, milestones, branch protection rules, and repository settings are all infrastructure, and all of them should live in a `.tf` file checked into your repository.

---

### **Pattern: INFRASTRUCTURE AS EXECUTABLE SPECIFICATION**

**Problem**

Your project's structure — milestones, issue templates, repository settings, branch protection — needs to be consistent, reproducible, and reviewable. Creating it manually means no audit trail, no reproducibility, and no way to catch errors before they reach the team.

**Context**

TaskFlow is a multi-chapter project. You need milestones for each development phase (Environment Setup, Core Actors, WebSocket Layer, AWS Deployment, Operations), issues for the work items within each phase, and a repository configured with sensible defaults. This configuration will be read by every new team member who joins the project.

**Solution**

Define the entire GitHub project structure in Terraform HCL files. A `.tf` file is an executable specification: it describes what should exist, not how to create it. Terraform's plan step shows you what will be created, modified, or destroyed before you apply anything. The apply step makes reality match the specification.

Start with the provider configuration:

```hcl
# terraform/github/versions.tf
terraform {
  required_version = ">= 1.7.0"

  required_providers {
    github = {
      source  = "integrations/github"
      version = "~> 6.0"
    }
  }

  backend "s3" {
    bucket         = "taskflow-terraform-state"
    key            = "github/terraform.tfstate"
    region         = "us-east-1"
    encrypt        = true
    dynamodb_table = "taskflow-terraform-locks"
  }
}

provider "github" {
  token = var.github_token
  owner = var.github_owner
}
```

The S3 backend stores Terraform state remotely. Multiple engineers can apply changes from different machines without stepping on each other, because the state file is locked via DynamoDB during applies. This is essential for team use.

Variables make the configuration portable:

```hcl
# terraform/github/variables.tf
variable "github_token" {
  type        = string
  description = "GitHub personal access token with repo and admin:org scopes"
  sensitive   = true
}

variable "github_owner" {
  type        = string
  description = "GitHub organization or user name owning the repository"
}

variable "repository_name" {
  type        = string
  default     = "taskflow"
  description = "Name of the GitHub repository to create"
}

variable "repository_description" {
  type        = string
  default     = "Real-time Kanban board built with JOTP on Java 26"
}

variable "default_branch" {
  type        = string
  default     = "main"
}
```

The `github_token` variable is marked `sensitive = true`. Terraform will not print it in plan or apply output. You pass it via an environment variable:

```bash
export TF_VAR_github_token="ghp_..."
export TF_VAR_github_owner="your-org-name"
```

Never put tokens in `.tf` files or `terraform.tfvars` files checked into source control.

The repository resource:

```hcl
# terraform/github/main.tf
resource "github_repository" "taskflow" {
  name        = var.repository_name
  description = var.repository_description
  visibility  = "private"

  has_issues   = true
  has_projects = false
  has_wiki     = false

  auto_init          = true
  gitignore_template = "Java"
  license_template   = "apache-2.0"

  default_branch = var.default_branch

  allow_merge_commit     = false
  allow_squash_merge     = true
  allow_rebase_merge     = false
  delete_branch_on_merge = true
}
```

This is a complete specification of the repository's settings. `allow_merge_commit = false` and `allow_rebase_merge = false` enforce squash merges only, which produces a clean linear history. `delete_branch_on_merge = true` keeps the repository tidy. These are opinions baked into the specification, visible in code review, enforced by Terraform.

Branch protection ensures the main branch cannot be pushed to directly:

```hcl
resource "github_branch_protection" "main" {
  repository_id = github_repository.taskflow.node_id
  pattern       = var.default_branch

  required_status_checks {
    strict   = true
    contexts = ["build", "test", "guard-validation"]
  }

  required_pull_request_reviews {
    required_approving_review_count = 1
    dismiss_stale_reviews           = true
    require_code_owner_reviews      = true
  }

  enforce_admins = true
}
```

`enforce_admins = true` means even repository administrators cannot bypass the branch protection. This is controversial in some organizations, but for a production system it is the right default. Admins bypassing protection are the most common source of "one quick fix" incidents.

**Consequences**

Anyone with access to the repository and the Terraform state bucket can recreate the entire GitHub project structure from scratch with three commands:

```bash
terraform init
terraform plan
terraform apply
```

New team members see the project structure in code before they look at GitHub. Changes to branch protection or repository settings go through pull request review. Accidental deletions are recoverable.

The tradeoff is that Terraform adds a dependency: you need an S3 bucket and DynamoDB table before you can initialize the backend. Chapter 3 covers creating these AWS resources with Terraform as well — the infrastructure bootstraps itself, layer by layer.

---

### **Pattern: STATE AS SINGLE SOURCE OF TRUTH**

**Problem**

GitHub milestones and issues get created, modified, and sometimes deleted by team members through the UI. After a few weeks, the Terraform state diverges from reality. Plans show unexpected diffs. Applies fail because resources exist that Terraform does not know about. You lose confidence in the specification.

**Context**

TaskFlow development spans multiple phases. The milestones and issues need to track real work, which means they will be updated as the project progresses. You need a strategy that keeps Terraform as the authoritative source while allowing the natural evolution of project state.

**Solution**

Define milestones and issues in Terraform, but use `lifecycle` blocks to prevent Terraform from destroying issues that have been closed or updated:

```hcl
# terraform/github/milestones.tf
locals {
  milestones = {
    "v0.1-environment" = {
      title       = "v0.1: Environment & Core Actors"
      description = "JDK 26, mvnd, dx-guard, first Proc<BoardState, BoardMsg>"
      due_date    = "2026-04-01"
    }
    "v0.2-state-machine" = {
      title       = "v0.2: Card State Machine"
      description = "StateMachine<CardState, CardEvent, CardData> with full supervision"
      due_date    = "2026-04-15"
    }
    "v0.3-websocket" = {
      title       = "v0.3: WebSocket Broadcasting"
      description = "Real-time card move events via EventManager"
      due_date    = "2026-05-01"
    }
    "v0.4-aws" = {
      title       = "v0.4: AWS Deployment"
      description = "ECS Fargate, RDS Aurora, ElastiCache, Route 53"
      due_date    = "2026-05-15"
    }
    "v0.5-ops" = {
      title       = "v0.5: Operations"
      description = "Observability, runbooks, chaos testing"
      due_date    = "2026-06-01"
    }
  }
}

resource "github_repository_milestone" "milestones" {
  for_each = local.milestones

  owner      = var.github_owner
  repository = github_repository.taskflow.name
  title      = each.value.title
  description = each.value.description
  due_date   = each.value.due_date
}
```

Using `for_each` with a local map means adding a new milestone is a one-line change to the `locals` block. Terraform plans the creation of the new resource and leaves existing milestones untouched.

Issues are more complex because they evolve — they get assigned, labeled, commented on, and closed. Define the foundational issues in Terraform, but accept that their state will drift:

```hcl
# terraform/github/issues.tf
locals {
  issues = {
    "setup-jdk26" = {
      title     = "Set up JDK 26 development environment"
      body      = <<-EOT
        ## Acceptance Criteria
        - [ ] JDK 26 EA installed at `/usr/lib/jvm/openjdk-26`
        - [ ] `java --version` reports 26
        - [ ] `--enable-preview` flag configured in pom.xml
        - [ ] `mvnd verify` passes on clean checkout

        ## Notes
        Use `.claude/setup.sh` for automated installation.
        Verify with `./dx.sh all`.
      EOT
      milestone = "v0.1-environment"
      labels    = ["setup", "environment"]
    }
    "first-proc" = {
      title     = "Implement Proc<BoardState, BoardMsg>"
      body      = <<-EOT
        ## Acceptance Criteria
        - [ ] `BoardMsg` sealed interface with AddCard, MoveCard, ArchiveColumn, GetSnapshot
        - [ ] `BoardState` record with Column and Card nested records
        - [ ] `BoardHandler` pure BiFunction passing all unit tests
        - [ ] `BoardIntegrationIT` passing with concurrent tell() test
        - [ ] Guard scan clean (no H_TODO, H_MOCK, H_STUB)

        ## Definition of Done
        `./dx.sh all` passes with zero violations.
      EOT
      milestone = "v0.1-environment"
      labels    = ["jotp", "actors", "core"]
    }
    "supervision-tree" = {
      title     = "Wire BoardRegistry with ONE_FOR_ONE Supervisor"
      body      = <<-EOT
        ## Acceptance Criteria
        - [ ] `BoardRegistry` creates Supervisor with ONE_FOR_ONE strategy
        - [ ] Each board has a named process: `board-{boardId}`
        - [ ] `ProcRef` used throughout (no raw Proc references)
        - [ ] Board crash does not affect other boards (integration test)
        - [ ] Restart count verified in supervisor test

        ## References
        See ARCHITECTURE.md Pattern 2: Bulkhead
      EOT
      milestone = "v0.1-environment"
      labels    = ["jotp", "supervision", "core"]
    }
    "card-state-machine" = {
      title     = "Implement StateMachine<CardState, CardEvent, CardData>"
      body      = <<-EOT
        ## Acceptance Criteria
        - [ ] `CardState` sealed interface: Backlog, InProgress, Done, Archived
        - [ ] `CardEvent` sealed interface: Assign, Start, Complete, Archive, Reopen
        - [ ] All legal transitions defined; illegal transitions return Stop(reason)
        - [ ] `CardStateMachineTest` property-based tests with jqwik

        ## Design Notes
        Illegal transitions should produce `Stop("Invalid transition: {state} -> {event}")`
        not exceptions. The state machine is a process; it should fail safely.
      EOT
      milestone = "v0.2-state-machine"
      labels    = ["jotp", "state-machine", "core"]
    }
  }
}

resource "github_issue" "issues" {
  for_each = local.issues

  repository = github_repository.taskflow.name
  title      = each.value.title
  body       = each.value.body
  labels     = each.value.labels

  # Resolve milestone number from the milestone resource
  milestone_number = github_repository_milestone.milestones[each.value.milestone].number

  lifecycle {
    # Ignore changes to state (open/closed) and assignments
    # These change naturally as work progresses
    ignore_changes = [
      state,
      assignees,
    ]
  }
}
```

The `lifecycle { ignore_changes }` block is the key to making STATE AS SINGLE SOURCE OF TRUTH practical. Terraform created the issue with the correct title, body, and milestone. If a team member closes the issue or reassigns it, Terraform will not reopen it or remove the assignee on the next apply. The issue's current state is owned by the team. The issue's definition — title, body, milestone — is owned by Terraform.

This is a deliberate design decision. Terraform owns the specification; humans own the execution state.

Labels also need to be defined before they can be applied to issues:

```hcl
# terraform/github/labels.tf
locals {
  labels = {
    "setup"        = { color = "0075ca", description = "Development environment setup" }
    "environment"  = { color = "e4e669", description = "Build tooling and infrastructure" }
    "jotp"         = { color = "7057ff", description = "JOTP framework primitives" }
    "actors"       = { color = "008672", description = "Proc and actor model patterns" }
    "supervision"  = { color = "b60205", description = "Supervisor trees and restart strategies" }
    "state-machine"= { color = "d93f0b", description = "StateMachine pattern" }
    "core"         = { color = "0e8a16", description = "Core TaskFlow business logic" }
    "aws"          = { color = "ff9f1a", description = "AWS infrastructure resources" }
    "ops"          = { color = "1d76db", description = "Operational concerns and runbooks" }
  }
}

resource "github_issue_label" "labels" {
  for_each = local.labels

  repository  = github_repository.taskflow.name
  name        = each.key
  color       = each.value.color
  description = each.value.description
}
```

Outputs make it easy to find created resources:

```hcl
# terraform/github/outputs.tf
output "repository_url" {
  value       = github_repository.taskflow.html_url
  description = "URL of the created GitHub repository"
}

output "repository_clone_url" {
  value       = github_repository.taskflow.ssh_clone_url
  description = "SSH clone URL"
}

output "milestone_numbers" {
  value = {
    for k, v in github_repository_milestone.milestones :
    k => v.number
  }
  description = "Map of milestone key to GitHub milestone number"
}

output "issue_numbers" {
  value = {
    for k, v in github_issue.issues :
    k => v.number
  }
  description = "Map of issue key to GitHub issue number"
}
```

**Consequences**

Running `terraform plan` after any change shows you exactly what GitHub state will be modified before you commit to it. A new team member can run `terraform plan` without the `github_token` to read the structure (plan will fail on API calls, but the local validation passes). The specification is self-documenting.

The `lifecycle { ignore_changes }` pattern accepts a tradeoff: if you change an issue's title in Terraform, Terraform will update it. But if a team member closes the issue, Terraform will not reopen it. This is the right tradeoff for project management resources.

---

### Running Terraform

**Initialize the backend**:

```bash
cd terraform/github
terraform init
```

Output:

```
Initializing the backend...
Successfully configured the backend "s3"!

Initializing provider plugins...
- Finding integrations/github versions matching "~> 6.0"...
- Installing integrations/github v6.3.1...

Terraform has been successfully initialized!
```

**Preview changes**:

```bash
terraform plan -out=tfplan
```

Output (abbreviated):

```
Terraform will perform the following actions:

  # github_repository.taskflow will be created
  + resource "github_repository" "taskflow" {
      + name        = "taskflow"
      + description = "Real-time Kanban board built with JOTP on Java 26"
      + visibility  = "private"
      ...
    }

  # github_repository_milestone.milestones["v0.1-environment"] will be created
  + resource "github_repository_milestone" "milestones" {
      + title    = "v0.1: Environment & Core Actors"
      + due_date = "2026-04-01"
    }

  # ... 4 more milestones, 4 issues, 9 labels, 1 branch protection

Plan: 20 to add, 0 to change, 0 to destroy.
```

Review the plan. Every resource in the `+` list should be there. Nothing should be in the `-` (destroy) list on a first apply.

**Apply**:

```bash
terraform apply tfplan
```

```
github_repository.taskflow: Creating...
github_repository.taskflow: Creation complete after 3s
github_issue_label.labels["jotp"]: Creating...
...
github_repository_milestone.milestones["v0.1-environment"]: Creating...
...
github_issue.issues["setup-jdk26"]: Creating...
...

Apply complete! Resources: 20 added, 0 changed, 0 destroyed.

Outputs:

repository_url = "https://github.com/your-org/taskflow"
milestone_numbers = {
  "v0.1-environment" = 1
  "v0.2-state-machine" = 2
  ...
}
issue_numbers = {
  "card-state-machine" = 4
  "first-proc" = 2
  "setup-jdk26" = 1
  "supervision-tree" = 3
}
```

Your project structure now exists in GitHub and in Terraform state. Clone the repository, open issue #1, assign it to yourself, and start working through Chapter 1's setup steps. When you close the issue, `terraform plan` will show no changes to that issue because `state` is in `ignore_changes`.

**Adding a new milestone**

When you reach the AWS deployment phase, add to the `locals` block in `milestones.tf`:

```hcl
"v0.6-performance" = {
  title       = "v0.6: Performance Benchmarking"
  description = "JMH benchmarks for ActorBenchmark, ParallelBenchmark, ResultBenchmark"
  due_date    = "2026-06-15"
}
```

Run `terraform plan`. You see:

```
  # github_repository_milestone.milestones["v0.6-performance"] will be created
  + resource "github_repository_milestone" "milestones" {
      + title = "v0.6: Performance Benchmarking"
    }

Plan: 1 to add, 0 to change, 0 to destroy.
```

One addition, zero modifications to existing resources. The plan is surgical. Apply it.

---

### What Have You Learned?

- **INFRASTRUCTURE AS EXECUTABLE SPECIFICATION**: Terraform HCL files describe what should exist, not how to create it. `terraform plan` previews changes; `terraform apply` makes them real. GitHub repositories, milestones, branch protection, and issues are all infrastructure that belongs in version control.

- **STATE AS SINGLE SOURCE OF TRUTH**: Terraform owns the definition of project structure; team members own the execution state (open/closed, assigned/unassigned). The `lifecycle { ignore_changes }` block makes this split explicit and prevents Terraform from clobbering human decisions.

- **Provider and backend configuration**: The GitHub Terraform provider authenticates via a personal access token passed as `TF_VAR_github_token`. State is stored in S3 with DynamoDB locking to support team use. These are standard patterns for any Terraform workspace.

- **`for_each` and locals**: Defining collections of similar resources (milestones, labels, issues) as `for_each` maps keeps the configuration DRY. Adding a resource is a one-line change to a `locals` block.

- **Outputs**: Terraform outputs make it easy to reference created resource identifiers (milestone numbers, issue numbers, repository URLs) without navigating the GitHub UI.

- **Sensitive variables**: Tokens and secrets are `sensitive = true` in variable declarations and passed via environment variables (`TF_VAR_*`). They never appear in `.tf` files or Terraform plan output.

With your environment configured, your guard system running, and your project structured in GitHub, you are ready to build the TaskFlow supervision tree in Chapter 3.
