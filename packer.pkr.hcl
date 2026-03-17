packer {
  required_version = ">= 1.8.0"
  required_plugins {
    docker = {
      version = ">= 1.0.0"
      source  = "github.com/hashicorp/docker"
    }
  }
}

variable "java_version" {
  default = "26"
}

variable "java_build" {
  default = "33"
}

variable "mvnd_version" {
  default = "2.0.0-rc-3"
}

variable "maven_version" {
  default = "4.0.0-rc-3"
}

variable "image_name" {
  default = "jotp-java26-base"
}

variable "image_tag" {
  default = "latest"
}

source "docker" "jotp" {
  image  = "ubuntu:24.04"
  commit = true

  changes = [
    "ENV JAVA_HOME=/opt/java",
    "ENV MAVEN_HOME=/opt/maven",
    "ENV MVND_HOME=/opt/mvnd",
    "ENV PATH=/opt/java/bin:/opt/maven/bin:/opt/mvnd/bin:/usr/local/bin:/usr/bin:/bin",
    "WORKDIR /app"
  ]
}

build {
  name = "jotp-java26-build"
  sources = ["source.docker.jotp"]

  provisioner "shell" {
    inline = [
      "apt-get update && apt-get install -y --no-install-recommends curl tar gzip wget ca-certificates bash git jq procps",
      "rm -rf /var/lib/apt/lists/*"
    ]
  }

  provisioner "shell" {
    inline = [
      "echo 'Installing Java 26 EA Build ${var.java_build}'",
      "mkdir -p /opt/java",
      "curl -fsSL \"https://download.java.net/java/early_access/jdk${var.java_version}/${var.java_build}/GPL/openjdk-${var.java_version}-ea+${var.java_build}_linux-x64_bin.tar.gz\" | tar -xzf - -C /opt/java --strip-components=1",
      "java --version"
    ]
    expect_disconnect = true
  }

  provisioner "shell" {
    inline = [
      "echo 'Installing Maven Daemon ${var.mvnd_version}'",
      "mkdir -p /opt/mvnd",
      "curl -fsSL \"https://github.com/apache/maven-mvnd/releases/download/${var.mvnd_version}/maven-mvnd-${var.mvnd_version}-linux-amd64.tar.gz\" | tar -xzf - -C /opt/mvnd --strip-components=1",
      "ln -s /opt/mvnd/bin/mvnd /usr/local/bin/mvnd",
      "ln -s /opt/mvnd/bin/mvnd /usr/local/bin/mvn",
      "ln -s /opt/mvnd/bin/mvnw /usr/local/bin/mvnw",
      "mvnd --version"
    ]
  }

  provisioner "shell" {
    inline = [
      "echo 'Installing Maven 4 ${var.maven_version}'",
      "mkdir -p /opt/maven",
      "curl -fsSL \"https://archive.apache.org/dist/maven/maven-${var.maven_version}/binaries/apache-maven-${var.maven_version}-bin.tar.gz\" | tar -xzf - -C /opt/maven --strip-components=1",
      "echo 'Maven 4 installation completed (Maven Daemon is primary)'"
    ]
  }

  provisioner "shell" {
    inline = [
      "echo 'Setting up environment for JOTP'",
      "mkdir -p /app /root/.m2/repository /root/.m2/mvnd",
      "echo 'export JAVA_HOME=/opt/java' > /etc/profile.d/java.sh",
      "echo 'export MAVEN_HOME=/opt/maven' >> /etc/profile.d/java.sh",
      "echo 'export MVND_HOME=/opt/mvnd' >> /etc/profile.d/java.sh",
      "echo 'export PATH=/opt/java/bin:/opt/maven/bin:/opt/mvnd/bin:$PATH' >> /etc/profile.d/java.sh",
      "chmod +x /etc/profile.d/java.sh",
      "echo 'Packer image build complete!'"
    ]
  }

  post-processor "docker-tag" {
    repository = "local/${var.image_name}"
    tags       = ["${var.image_tag}"]
  }
}