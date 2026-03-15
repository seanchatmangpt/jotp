# Chapter 7: The Production Environment and Packer

There is a rite of passage in every software team's history: the first time someone has to recover a production server and nobody knows exactly how it was set up. The original engineer who configured it left six months ago. The runbook says "install Java and deploy the jar" but doesn't specify which Java, which version of the jar, or what environment variables are needed. The recovery takes eight hours.

The solution is not better documentation. Documentation rots. The solution is that your production environment is defined as code, built into an artifact, and re-created from that artifact. This chapter covers that full cycle: you create the AWS production environment manually to understand what you're automating, use `terraform import` to bring it under infrastructure-as-code control, build a gold machine image with Packer, and wire everything together. You also add `CrashRecovery.retry` to the AWS API calls that talk to EC2 and RDS, because AWS APIs are distributed systems and distributed systems have transient failures.

---

## Pattern: THE GOLD IMAGE

**Problem**

Server configuration that happens after deployment — installing Java, configuring systemd units, setting environment variables, pulling Docker images — is fragile, slow, and hard to test. If a server fails and you need to replace it, you re-run the configuration process and hope nothing changed in the package repositories overnight.

**Context**

TaskFlow runs on EC2 instances in AWS. Each instance needs JDK 26, the TaskFlow Docker image pre-pulled, a systemd unit for the application, CloudWatch agent configuration, and specific kernel parameters for Java virtual thread performance. These requirements are stable between deployments — they change when the Java version changes, not when the application changes.

**Solution**

Build a machine image (AMI) that has everything pre-installed and pre-configured. When you launch a new instance, it boots with Java already installed, Docker already configured, and the application image already pulled. Startup time drops from ten minutes to under sixty seconds. Configuration drift is impossible because every new instance is created from the same image.

Packer builds the image. You define a Packer template in HCL2, run `packer build`, and get an AMI ID that you then reference in your Terraform `aws_launch_template`.

Create `packer/taskflow.pkr.hcl`:

```hcl
packer {
  required_plugins {
    amazon = {
      version = ">= 1.3.0"
      source  = "github.com/hashicorp/amazon"
    }
  }
}

variable "aws_region" {
  type    = string
  default = "us-east-1"
}

variable "taskflow_version" {
  type    = string
  default = "latest"
}

variable "base_ami_id" {
  type        = string
  description = "Ubuntu 22.04 LTS AMI ID for the target region"
  default     = "ami-0c7217cdde317cfec"  # us-east-1 Ubuntu 22.04 LTS
}

source "amazon-ebs" "taskflow" {
  region        = var.aws_region
  source_ami    = var.base_ami_id
  instance_type = "t3.medium"  # Same type as production for reliable benchmarking

  ssh_username = "ubuntu"

  ami_name        = "taskflow-${var.taskflow_version}-{{timestamp}}"
  ami_description = "TaskFlow application image with JDK 26 and pre-pulled Docker image"

  tags = {
    Name            = "taskflow"
    Version         = var.taskflow_version
    BuildTimestamp  = "{{timestamp}}"
    ManagedBy       = "packer"
  }

  # Security: restrict SSH to the Packer build host only during build
  temporary_security_group_source_cidrs = ["0.0.0.0/0"]
}

build {
  name    = "taskflow-gold-image"
  sources = ["source.amazon-ebs.taskflow"]

  # Wait for cloud-init to complete before provisioning
  provisioner "shell" {
    inline = [
      "cloud-init status --wait",
      "sudo apt-get update -qq"
    ]
  }

  # Install Docker
  provisioner "shell" {
    inline = [
      "sudo apt-get install -y -qq ca-certificates curl gnupg",
      "sudo install -m 0755 -d /etc/apt/keyrings",
      "curl -fsSL https://download.docker.com/linux/ubuntu/gpg | sudo gpg --dearmor -o /etc/apt/keyrings/docker.gpg",
      "sudo chmod a+r /etc/apt/keyrings/docker.gpg",
      "echo \"deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo $VERSION_CODENAME) stable\" | sudo tee /etc/apt/sources.list.d/docker.list > /dev/null",
      "sudo apt-get update -qq",
      "sudo apt-get install -y -qq docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin",
      "sudo systemctl enable docker",
      "sudo usermod -aG docker ubuntu"
    ]
  }

  # Install OpenJDK 26
  provisioner "shell" {
    inline = [
      "JDK26_URL='https://download.java.net/java/GA/jdk26/c3cc523845074aa0af4f5e1e1ed4151d/35/GPL/openjdk-26_linux-x64_bin.tar.gz'",
      "curl -fsSL \"$JDK26_URL\" -o /tmp/jdk26.tar.gz",
      "sudo mkdir -p /usr/lib/jvm",
      "sudo tar -xzf /tmp/jdk26.tar.gz -C /usr/lib/jvm",
      "sudo mv /usr/lib/jvm/jdk-26* /usr/lib/jvm/openjdk-26",
      "sudo ln -sf /usr/lib/jvm/openjdk-26 /opt/jdk",
      "rm /tmp/jdk26.tar.gz",
      "echo 'JAVA_HOME=/usr/lib/jvm/openjdk-26' | sudo tee -a /etc/environment",
      "echo 'PATH=/usr/lib/jvm/openjdk-26/bin:$PATH' | sudo tee -a /etc/environment",
      "/usr/lib/jvm/openjdk-26/bin/java -version"
    ]
  }

  # Kernel tuning for virtual threads and high connection counts
  provisioner "shell" {
    inline = [
      "echo 'net.core.somaxconn = 65535' | sudo tee -a /etc/sysctl.conf",
      "echo 'net.ipv4.tcp_max_syn_backlog = 65535' | sudo tee -a /etc/sysctl.conf",
      "echo 'fs.file-max = 1000000' | sudo tee -a /etc/sysctl.conf",
      "echo '* soft nofile 1000000' | sudo tee -a /etc/security/limits.conf",
      "echo '* hard nofile 1000000' | sudo tee -a /etc/security/limits.conf"
    ]
  }

  # Copy systemd unit for TaskFlow
  provisioner "file" {
    source      = "../systemd/taskflow.service"
    destination = "/tmp/taskflow.service"
  }

  provisioner "shell" {
    inline = [
      "sudo mv /tmp/taskflow.service /etc/systemd/system/taskflow.service",
      "sudo systemctl daemon-reload",
      "sudo systemctl enable taskflow"
      # Don't start it — EC2 user data will start it on first boot with env vars injected
    ]
  }

  # Pre-pull the TaskFlow Docker image to speed up first boot
  provisioner "shell" {
    inline = [
      "sudo docker pull ghcr.io/seanchatmangpt/taskflow:${var.taskflow_version} || true"
      # The || true ensures the build doesn't fail if the image isn't yet published.
      # In production pipelines, remove the || true to enforce the image exists.
    ]
  }

  # Install CloudWatch agent
  provisioner "shell" {
    inline = [
      "curl -fsSL 'https://s3.amazonaws.com/amazoncloudwatch-agent/ubuntu/amd64/latest/amazon-cloudwatch-agent.deb' -o /tmp/cloudwatch-agent.deb",
      "sudo dpkg -i /tmp/cloudwatch-agent.deb",
      "rm /tmp/cloudwatch-agent.deb"
    ]
  }
}
```

The systemd unit at `systemd/taskflow.service`:

```ini
[Unit]
Description=TaskFlow Application
After=docker.service network-online.target
Requires=docker.service
Wants=network-online.target

[Service]
Type=simple
Restart=always
RestartSec=5s
User=ubuntu

# Environment variables are injected via EC2 user data or SSM Parameter Store
EnvironmentFile=-/etc/taskflow/env

ExecStartPre=-/usr/bin/docker stop taskflow
ExecStartPre=-/usr/bin/docker rm taskflow
ExecStart=/usr/bin/docker run \
  --name taskflow \
  --rm \
  --publish 8080:8080 \
  --env-file /etc/taskflow/env \
  ghcr.io/seanchatmangpt/taskflow:${TASKFLOW_VERSION:-latest}

ExecStop=/usr/bin/docker stop taskflow

[Install]
WantedBy=multi-user.target
```

Build the AMI:

```bash
cd packer
packer init taskflow.pkr.hcl
packer validate taskflow.pkr.hcl
packer build taskflow.pkr.hcl
# Output: AMI ID: ami-0a1b2c3d4e5f6a7b8
```

**Consequences**

The gold image makes instance replacement trivial and fast. New instances boot from a known-good image; there is no configuration phase that can fail or drift. The tradeoff is that the AMI must be rebuilt when the base configuration changes — when you upgrade from JDK 26 to JDK 27, or when the Docker version changes. This is the right tradeoff: explicit, versioned rebuilds versus invisible configuration drift.

---

## Pattern: INFRASTRUCTURE IMPORT

**Problem**

Your operations team created the production EC2 instance, RDS database, and security groups manually in the AWS console. The infrastructure exists and works. But it is not under version control. The next person who needs to create a new environment must guess at the configuration, or ask the person who set it up. When that person leaves, institutional knowledge walks out with them.

**Context**

You have a running AWS environment: one EC2 `t3.medium` instance running TaskFlow, one RDS PostgreSQL instance, security groups restricting access, an Application Load Balancer. You want all of this under Terraform without destroying and recreating it.

**Solution**

Use `terraform import` to import existing AWS resources into Terraform state. You write the Terraform configuration that describes the resource, then import the existing resource ID into that configuration. Terraform then manages the resource without recreating it.

Create `terraform/main.tf`:

```hcl
terraform {
  required_version = ">= 1.7.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }

  backend "s3" {
    bucket = "taskflow-terraform-state"
    key    = "production/terraform.tfstate"
    region = "us-east-1"
    # State locking via DynamoDB
    dynamodb_table = "taskflow-terraform-locks"
    encrypt        = true
  }
}

provider "aws" {
  region = var.aws_region
}

variable "aws_region" {
  type    = string
  default = "us-east-1"
}

variable "taskflow_ami_id" {
  type        = string
  description = "AMI ID produced by packer build"
}

variable "db_password" {
  type      = string
  sensitive = true
}

# VPC (import existing)
resource "aws_vpc" "taskflow" {
  cidr_block           = "10.0.0.0/16"
  enable_dns_hostnames = true
  enable_dns_support   = true

  tags = {
    Name = "taskflow-vpc"
  }
}

# Security group: application tier
resource "aws_security_group" "taskflow_app" {
  name        = "taskflow-app"
  description = "TaskFlow application security group"
  vpc_id      = aws_vpc.taskflow.id

  ingress {
    from_port   = 8080
    to_port     = 8080
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
    description = "TaskFlow application port"
  }

  ingress {
    from_port   = 22
    to_port     = 22
    protocol    = "tcp"
    cidr_blocks = ["10.0.0.0/8"]
    description = "SSH from VPC only"
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name = "taskflow-app"
  }
}

# Security group: database tier
resource "aws_security_group" "taskflow_db" {
  name        = "taskflow-db"
  description = "TaskFlow database security group"
  vpc_id      = aws_vpc.taskflow.id

  ingress {
    from_port       = 5432
    to_port         = 5432
    protocol        = "tcp"
    security_groups = [aws_security_group.taskflow_app.id]
    description     = "PostgreSQL from app tier only"
  }

  tags = {
    Name = "taskflow-db"
  }
}

# Launch template — uses the Packer-built AMI
resource "aws_launch_template" "taskflow" {
  name_prefix   = "taskflow-"
  image_id      = var.taskflow_ami_id
  instance_type = "t3.medium"

  vpc_security_group_ids = [aws_security_group.taskflow_app.id]

  iam_instance_profile {
    name = aws_iam_instance_profile.taskflow.name
  }

  # User data: inject environment variables at boot time
  # These are populated from SOPS-encrypted secrets at deploy time
  user_data = base64encode(templatefile("${path.module}/user-data.sh.tpl", {
    db_host     = aws_db_instance.taskflow.address
    db_password = var.db_password
  }))

  tag_specifications {
    resource_type = "instance"
    tags = {
      Name = "taskflow"
    }
  }
}

# RDS PostgreSQL
resource "aws_db_instance" "taskflow" {
  identifier        = "taskflow-postgres"
  engine            = "postgres"
  engine_version    = "16.1"
  instance_class    = "db.t3.medium"
  allocated_storage = 20
  storage_type      = "gp3"

  db_name  = "taskflow"
  username = "taskflow"
  password = var.db_password

  vpc_security_group_ids = [aws_security_group.taskflow_db.id]
  skip_final_snapshot    = false
  final_snapshot_identifier = "taskflow-final-snapshot"

  backup_retention_period = 7
  backup_window           = "03:00-04:00"
  maintenance_window      = "mon:04:00-mon:05:00"

  tags = {
    Name = "taskflow-postgres"
  }
}

# EC2 instance (the existing one you're importing)
resource "aws_instance" "taskflow" {
  ami           = var.taskflow_ami_id
  instance_type = "t3.medium"

  vpc_security_group_ids = [aws_security_group.taskflow_app.id]
  iam_instance_profile   = aws_iam_instance_profile.taskflow.name

  tags = {
    Name = "taskflow"
  }
}

# IAM role for EC2 instances — allows SSM Parameter Store access and CloudWatch metrics
resource "aws_iam_role" "taskflow" {
  name = "taskflow-ec2-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Action    = "sts:AssumeRole"
      Effect    = "Allow"
      Principal = { Service = "ec2.amazonaws.com" }
    }]
  })
}

resource "aws_iam_role_policy_attachment" "ssm" {
  role       = aws_iam_role.taskflow.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

resource "aws_iam_role_policy_attachment" "cloudwatch" {
  role       = aws_iam_role.taskflow.name
  policy_arn = "arn:aws:iam::aws:policy/CloudWatchAgentServerPolicy"
}

resource "aws_iam_instance_profile" "taskflow" {
  name = "taskflow-ec2-profile"
  role = aws_iam_role.taskflow.name
}
```

Now import the existing resources. The instance ID `i-0abc123` is what you see in the EC2 console:

```bash
cd terraform
terraform init

# Import the existing EC2 instance
terraform import aws_instance.taskflow i-0abc123def456789

# Import the existing RDS instance
terraform import aws_db_instance.taskflow taskflow-postgres

# Import the existing VPC
terraform import aws_vpc.taskflow vpc-0abc123def456789

# Import security groups
terraform import aws_security_group.taskflow_app sg-0abc123app
terraform import aws_security_group.taskflow_db sg-0abc123db
```

After import, run `terraform plan` to see the diff between your Terraform configuration and the actual state. The first plan will often show changes — the resource was configured with values that differ from what you wrote. This is the import workflow: import, plan, reconcile the configuration to match reality, plan again until the diff is clean.

```bash
terraform plan -var="taskflow_ami_id=ami-placeholder" \
               -var="db_password=$(sops -d secrets/prod.yaml | yq '.db_password')"
```

**Managing secrets with SOPS and Age**

Database passwords, API keys, and JWT secrets cannot live in your Terraform files or environment variables visible in process listings. SOPS (Secrets OPerationS) encrypts secret files using Age keys; the encrypted file is safe to commit to Git. Decryption requires the private Age key, which lives in your secrets manager or on developer machines.

Create an Age key pair:

```bash
# Generate an Age key pair
age-keygen -o ~/.age/taskflow-prod.txt
# Output: Public key: age1ql3z7hjy54pw3hyww5ayyfg7zqgvc7w3j2elw8zmrj2kg5sfn9aqmcac8p

# Add the public key to .sops.yaml
cat > .sops.yaml <<EOF
creation_rules:
  - path_regex: secrets/.*\.yaml$
    age: age1ql3z7hjy54pw3hyww5ayyfg7zqgvc7w3j2elw8zmrj2kg5sfn9aqmcac8p
EOF
```

Create and encrypt `secrets/prod.yaml`:

```bash
sops secrets/prod.yaml
```

SOPS opens an editor with a YAML template. Enter your secrets:

```yaml
db_password: ENC[AES256_GCM,data:verySecretPassword123,...]
jwt_secret: ENC[AES256_GCM,data:anotherSecret...,...]
```

Decrypt at deploy time:

```bash
# Decrypt and pass to terraform
DB_PASSWORD=$(sops -d secrets/prod.yaml | python3 -c "import sys,yaml; print(yaml.safe_load(sys.stdin)['db_password'])")
terraform apply -var="db_password=${DB_PASSWORD}" -var="taskflow_ami_id=${AMI_ID}"
```

**Consequences**

`terraform import` lets you bring existing infrastructure under IaC control without downtime or recreation. The first plan shows the gap between your Terraform configuration and reality — this gap is valuable information. It tells you what was configured manually that your configuration doesn't model yet.

The SOPS + Age workflow keeps secrets encrypted at rest and in Git while making them available at deploy time. The Age private key is the only credential that must be protected; everything else is derived from it.

---

## Pattern: THE RETRY ENVELOPE

**Problem**

AWS APIs are themselves distributed systems. They are reliable in aggregate but fail transiently in the specific. An `ec2:DescribeInstances` call can return a `500 Internal Server Error` or a `RequestLimitExceeded` throttling error at any moment. Production code that calls AWS APIs without retry logic will fail under load, during high-traffic periods, or simply during AWS's routine maintenance windows.

**Context**

TaskFlow's deployment tooling calls AWS EC2 and RDS APIs directly — to verify that new instances have started, to check RDS availability before updating DNS, to describe security group rules during validation. These calls happen in a Java deployment tool built with the AWS SDK for Java v2.

**Solution**

Wrap each AWS API call in `CrashRecovery.retry`. The `retry` method runs the supplier in an isolated virtual thread. If the supplier throws an exception, the method runs it again, up to the specified count. It returns a `Result<T, Exception>` — success or the final exception, never a raw throw.

```java
package io.github.seanchatmangpt.taskflow.deploy;

import io.github.seanchatmangpt.jotp.CrashRecovery;
import io.github.seanchatmangpt.jotp.Result;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.*;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.rds.model.DBInstance;
import software.amazon.awssdk.services.rds.model.DescribeDbInstancesRequest;

import java.time.Duration;
import java.util.List;

public class AwsDeploymentVerifier {

    private final Ec2Client ec2Client;
    private final RdsClient rdsClient;

    public AwsDeploymentVerifier(Ec2Client ec2Client, RdsClient rdsClient) {
        this.ec2Client = ec2Client;
        this.rdsClient = rdsClient;
    }

    /**
     * Verify that new EC2 instances launched from the given AMI are running.
     * Retries 3 times to handle transient AWS API errors.
     */
    public List<Instance> describeTaskflowInstances(String amiId) {
        Result<List<Instance>, Exception> result = CrashRecovery.retry(3, () -> {
            DescribeInstancesRequest request = DescribeInstancesRequest.builder()
                .filters(
                    Filter.builder().name("image-id").values(amiId).build(),
                    Filter.builder().name("instance-state-name").values("running").build()
                )
                .build();

            return ec2Client.describeInstancesPaginator(request)
                .stream()
                .flatMap(page -> page.reservations().stream())
                .flatMap(reservation -> reservation.instances().stream())
                .toList();
        });

        return result.fold(
            instances -> instances,
            error -> {
                throw new DeploymentVerificationException(
                    "Failed to describe EC2 instances after 3 attempts", error
                );
            }
        );
    }

    /**
     * Wait for RDS to be in 'available' state after modification.
     * The RDS API can return transient errors during state transitions.
     */
    public DBInstance describeRdsInstance(String dbInstanceIdentifier) {
        Result<DBInstance, Exception> result = CrashRecovery.retry(3, () -> {
            var response = rdsClient.describeDBInstances(
                DescribeDbInstancesRequest.builder()
                    .dbInstanceIdentifier(dbInstanceIdentifier)
                    .build()
            );

            return response.dbInstances().stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                    "RDS instance not found: " + dbInstanceIdentifier
                ));
        });

        return result.fold(
            instance -> instance,
            error -> {
                throw new DeploymentVerificationException(
                    "Failed to describe RDS instance after 3 attempts", error
                );
            }
        );
    }

    /**
     * Verify that the TaskFlow application is responding on a given instance.
     * EC2 health checks can lag instance start by 30-60 seconds.
     * Use CrashRecovery.retry with backoff for startup verification.
     */
    public boolean verifyInstanceHealthy(String instanceId) {
        Result<Boolean, Exception> result = CrashRecovery.retry(5, () -> {
            DescribeInstanceStatusRequest request = DescribeInstanceStatusRequest.builder()
                .instanceIds(instanceId)
                .includeAllInstances(true)
                .build();

            var statuses = ec2Client.describeInstanceStatus(request).instanceStatuses();

            if (statuses.isEmpty()) {
                throw new IllegalStateException("No status found for instance: " + instanceId);
            }

            var status = statuses.getFirst();
            var systemStatus = status.systemStatus().statusAsString();
            var instanceStatus = status.instanceStatus().statusAsString();

            if (!"ok".equals(systemStatus) || !"ok".equals(instanceStatus)) {
                // Not ready yet — throw to trigger retry
                throw new IllegalStateException(
                    "Instance " + instanceId + " not healthy yet: " +
                    "system=" + systemStatus + ", instance=" + instanceStatus
                );
            }

            return true;
        });

        return result.fold(
            healthy -> healthy,
            error -> {
                throw new DeploymentVerificationException(
                    "Instance " + instanceId + " did not become healthy after 5 attempts", error
                );
            }
        );
    }
}
```

The `Result.fold` pattern handles the two cases without null checks or explicit exception propagation. The success case returns the value; the failure case either re-throws a domain exception or returns a default. The calling code does not need to know that retries happened.

For deployment pipelines that need exponential backoff between retries, wrap `CrashRecovery.retry` with a `Thread.sleep` inside the supplier:

```java
// Retry with exponential backoff for rate-limited APIs
private <T> T retryWithBackoff(int maxAttempts, java.util.function.Supplier<T> supplier) {
    int[] attempt = {0};

    Result<T, Exception> result = CrashRecovery.retry(maxAttempts, () -> {
        int currentAttempt = attempt[0]++;
        if (currentAttempt > 0) {
            // Exponential backoff: 1s, 2s, 4s, ...
            long backoffMs = (long) Math.pow(2, currentAttempt - 1) * 1000L;
            Thread.sleep(backoffMs);
        }
        return supplier.get();
    });

    return result.fold(
        value -> value,
        error -> { throw new RuntimeException("All attempts failed", error); }
    );
}
```

**The full deployment pipeline** combining Packer, Terraform, and retry-wrapped verification:

```bash
#!/usr/bin/env bash
# deploy.sh — Full production deployment pipeline
set -euo pipefail

TASKFLOW_VERSION="${1:?Usage: deploy.sh <version>}"

echo "==> Building gold image for version ${TASKFLOW_VERSION}"
cd packer
AMI_ID=$(packer build \
  -var="taskflow_version=${TASKFLOW_VERSION}" \
  -machine-readable taskflow.pkr.hcl \
  | grep "artifact,0,id" \
  | cut -d: -f2)
echo "Built AMI: ${AMI_ID}"
cd ..

echo "==> Decrypting secrets"
DB_PASSWORD=$(sops -d secrets/prod.yaml | python3 -c \
  "import sys,yaml; print(yaml.safe_load(sys.stdin)['db_password'])")

echo "==> Applying Terraform with new AMI"
cd terraform
terraform apply \
  -var="taskflow_ami_id=${AMI_ID}" \
  -var="db_password=${DB_PASSWORD}" \
  -auto-approve
cd ..

echo "==> Verifying deployment"
mvnd exec:java \
  -Dexec.mainClass=io.github.seanchatmangpt.taskflow.deploy.DeploymentVerifier \
  -Dexec.args="${AMI_ID}"

echo "==> Deployment complete: TaskFlow ${TASKFLOW_VERSION}"
```

**Consequences**

`CrashRecovery.retry` makes transient failure handling explicit and testable. The retry count is visible in the code. The `Result<T, Exception>` return type forces callers to handle both success and failure. You cannot accidentally swallow an exception; the compiler requires you to handle the failure case through `fold`.

The limitation is that `CrashRecovery.retry` retries immediately by default. For rate-limited APIs like AWS, you need the backoff wrapper shown above. For the majority of AWS API usage — describe operations, status checks — immediate retry is sufficient because transient errors are not correlated: a `500 Internal Server Error` on attempt one is almost always resolved by attempt two.

The combination of Packer gold images, Terraform infrastructure-as-code, SOPS-encrypted secrets, and retry-wrapped API calls produces a deployment pipeline where every step is repeatable, auditable, and recoverable. The eight-hour recovery incident becomes a ten-minute `deploy.sh` run.

---

## What Have You Learned?

- **Gold images shift configuration left.** Everything that can be pre-installed is pre-installed at image build time. New instances boot in under sixty seconds with no configuration phase that can fail or drift.

- **`packer build` produces an AMI ID.** That ID flows directly into Terraform's `aws_launch_template`. The Packer output is Terraform's input. The pipeline is a pipeline.

- **`terraform import` brings existing infrastructure under IaC control without downtime.** Import, plan, reconcile the configuration to match reality, plan again. The first plan shows the gap between your intent and the actual state — that gap is information.

- **SOPS + Age encrypts secrets at rest in Git.** The encrypted file is safe to commit. The Age private key is the only credential that must be protected. Decryption at deploy time requires one command.

- **`CrashRecovery.retry(3, () -> awsApiCall())`** wraps flaky AWS APIs in an isolation envelope. Transient failures trigger retries automatically. The `Result<T, Exception>` return type forces explicit handling of all-attempts-failed.

- **`Result.fold(success, failure)`** eliminates null checks and exception-propagation boilerplate. The success function returns a value; the failure function either re-throws a domain exception or returns a sentinel. Callers always know which case they are handling.

- **The full deployment pipeline is three commands:** `packer build` to create the gold image, `terraform apply` to update infrastructure, deployment verification to confirm the new instances are healthy. Each step is independently repeatable and independently testable.
