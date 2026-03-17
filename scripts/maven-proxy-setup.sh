#!/usr/bin/env bash
#
# Maven Proxy Setup Script
# Configures Maven to use local proxy (Nexus or Artifactory) for faster builds
#
# Usage:
#   ./scripts/maven-proxy-setup.sh [--nexus|--artifactory|--direct|--status]
#
# Options:
#   --nexus           Configure Maven to use Nexus (port 8081)
#   --artifactory     Configure Maven to use Artifactory (port 8082)
#   --direct          Use Maven Central directly (no proxy)
#   --status          Show current proxy configuration
#   --test            Test proxy connectivity
#   --help            Show this help message
#

set -euo pipefail

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

# Configuration
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
SETTINGS_TEMPLATE="${PROJECT_ROOT}/.mvn/settings-template.xml"
M2_SETTINGS="${HOME}/.m2/settings.xml"
M2_DIR="${HOME}/.m2"

# Logging functions
log_info() { echo -e "${BLUE}[INFO]${NC} $*"; }
log_success() { echo -e "${GREEN}[✓]${NC} $*"; }
log_warn() { echo -e "${YELLOW}[WARN]${NC} $*"; }
log_error() { echo -e "${RED}[ERROR]${NC} $*"; }

# Show help
show_help() {
    cat << 'EOF'
Maven Proxy Setup Script

Usage:
  ./scripts/maven-proxy-setup.sh [OPTIONS]

Options:
  --nexus           Configure to use Nexus proxy (port 8081)
  --artifactory     Configure to use Artifactory (port 8082)
  --direct          Use Maven Central directly (no proxy)
  --status          Show current proxy configuration
  --test            Test proxy connectivity
  --help            Show this help message

Examples:
  # Configure Nexus proxy
  ./scripts/maven-proxy-setup.sh --nexus

  # Test current proxy configuration
  ./scripts/maven-proxy-setup.sh --test

  # Reset to direct Maven Central
  ./scripts/maven-proxy-setup.sh --direct

  # Check current status
  ./scripts/maven-proxy-setup.sh --status
EOF
}

# Ensure .m2 directory exists
setup_m2_directory() {
    if [ ! -d "$M2_DIR" ]; then
        log_info "Creating .m2 directory at $M2_DIR"
        mkdir -p "$M2_DIR"
        chmod 700 "$M2_DIR"
        log_success ".m2 directory created"
    fi
}

# Backup existing settings.xml
backup_settings() {
    if [ -f "$M2_SETTINGS" ]; then
        local backup="${M2_SETTINGS}.backup.$(date +%s)"
        log_info "Backing up existing settings.xml to $backup"
        cp "$M2_SETTINGS" "$backup"
        log_success "Backup created"
    fi
}

# Configure Nexus proxy
configure_nexus() {
    log_info "Configuring Maven for Nexus proxy (localhost:8081)..."

    setup_m2_directory
    backup_settings

    cat > "$M2_SETTINGS" << 'NEXUS_SETTINGS'
<?xml version="1.0" encoding="UTF-8"?>
<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0"
          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
          xsi:schemaLocation="http://maven.apache.org/SETTINGS/1.0.0
                              http://maven.apache.org/xsd/settings-1.0.0.xsd">

    <proxies>
        <proxy>
            <id>nexus-proxy</id>
            <active>true</active>
            <protocol>http</protocol>
            <host>localhost</host>
            <port>8081</port>
            <nonProxyHosts>localhost|127.0.0.1</nonProxyHosts>
        </proxy>
    </proxies>

    <mirrors>
        <mirror>
            <id>nexus-mirror</id>
            <mirrorOf>central</mirrorOf>
            <url>http://localhost:8081/nexus/repository/maven-public/</url>
        </mirror>
    </mirrors>

    <localRepository>${user.home}/.m2/repository</localRepository>

</settings>
NEXUS_SETTINGS

    chmod 600 "$M2_SETTINGS"
    log_success "Nexus proxy configuration applied"
    log_info "To use: export MAVEN_OPTS='-s $M2_SETTINGS'"
}

# Configure Artifactory proxy
configure_artifactory() {
    log_info "Configuring Maven for Artifactory proxy (localhost:8082)..."

    setup_m2_directory
    backup_settings

    cat > "$M2_SETTINGS" << 'ARTIFACTORY_SETTINGS'
<?xml version="1.0" encoding="UTF-8"?>
<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0"
          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
          xsi:schemaLocation="http://maven.apache.org/SETTINGS/1.0.0
                              http://maven.apache.org/xsd/settings-1.0.0.xsd">

    <proxies>
        <proxy>
            <id>artifactory-proxy</id>
            <active>true</active>
            <protocol>http</protocol>
            <host>localhost</host>
            <port>8082</port>
            <nonProxyHosts>localhost|127.0.0.1</nonProxyHosts>
        </proxy>
    </proxies>

    <mirrors>
        <mirror>
            <id>artifactory-mirror</id>
            <mirrorOf>*</mirrorOf>
            <url>http://localhost:8082/artifactory/repo/</url>
        </mirror>
    </mirrors>

    <localRepository>${user.home}/.m2/repository</localRepository>

</settings>
ARTIFACTORY_SETTINGS

    chmod 600 "$M2_SETTINGS"
    log_success "Artifactory proxy configuration applied"
    log_info "To use: export MAVEN_OPTS='-s $M2_SETTINGS'"
}

# Configure direct Maven Central (no proxy)
configure_direct() {
    log_info "Configuring Maven for direct Maven Central access..."

    setup_m2_directory
    backup_settings

    cat > "$M2_SETTINGS" << 'DIRECT_SETTINGS'
<?xml version="1.0" encoding="UTF-8"?>
<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0"
          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
          xsi:schemaLocation="http://maven.apache.org/SETTINGS/1.0.0
                              http://maven.apache.org/xsd/settings-1.0.0.xsd">

    <repositories>
        <repository>
            <id>central</id>
            <name>Maven Central Repository</name>
            <url>https://repo.maven.apache.org/maven2</url>
            <snapshots>
                <enabled>false</enabled>
            </snapshots>
        </repository>
    </repositories>

    <localRepository>${user.home}/.m2/repository</localRepository>

</settings>
DIRECT_SETTINGS

    chmod 600 "$M2_SETTINGS"
    log_success "Direct Maven Central configuration applied"
}

# Show current configuration
show_status() {
    log_info "Current Maven Configuration:"

    if [ ! -f "$M2_SETTINGS" ]; then
        log_warn "No settings.xml found at: $M2_SETTINGS"
        log_info "Using default Maven configuration (Maven Central)"
        return
    fi

    echo ""
    if grep -q "nexus" "$M2_SETTINGS" 2>/dev/null; then
        log_info "Using: Nexus proxy (localhost:8081)"
    elif grep -q "artifactory" "$M2_SETTINGS" 2>/dev/null; then
        log_info "Using: Artifactory proxy (localhost:8082)"
    else
        log_info "Using: Direct Maven Central access (no proxy)"
    fi

    echo ""
    echo "Settings file: $M2_SETTINGS"
    echo "To view configuration: cat $M2_SETTINGS"
}

# Test proxy connectivity
test_proxy() {
    log_info "Testing Maven proxy connectivity..."

    # Determine active proxy
    local proxy_type="unknown"
    local proxy_url=""

    if [ -f "$M2_SETTINGS" ]; then
        if grep -q "nexus" "$M2_SETTINGS" 2>/dev/null; then
            proxy_type="Nexus"
            proxy_url="http://localhost:8081/service/rest/v1/status"
        elif grep -q "artifactory" "$M2_SETTINGS" 2>/dev/null; then
            proxy_type="Artifactory"
            proxy_url="http://localhost:8082/"
        fi
    fi

    log_info "Testing $proxy_type proxy..."

    if [ -n "$proxy_url" ]; then
        if curl -s -f "$proxy_url" > /dev/null 2>&1; then
            log_success "$proxy_type proxy is accessible at $proxy_url"
        else
            log_error "$proxy_type proxy is NOT accessible at $proxy_url"
            log_info "Make sure Docker containers are running: docker-compose ps"
            return 1
        fi
    else
        log_info "Using direct Maven Central (no proxy to test)"
    fi

    # Test Maven dependency resolution
    log_info "Testing Maven dependency resolution..."
    cd "$PROJECT_ROOT"

    if mvn dependency:resolve -q 2>/dev/null; then
        log_success "Maven can resolve dependencies"
    else
        log_warn "Maven dependency resolution test failed"
        log_info "Try running: mvn dependency:resolve -X for debug output"
        return 1
    fi
}

# Main function
main() {
    local action="help"

    if [ $# -gt 0 ]; then
        action="$1"
    fi

    case "$action" in
        --nexus)
            configure_nexus
            ;;
        --artifactory)
            configure_artifactory
            ;;
        --direct)
            configure_direct
            ;;
        --status)
            show_status
            ;;
        --test)
            test_proxy
            ;;
        --help|-h)
            show_help
            ;;
        *)
            log_error "Unknown option: $action"
            echo ""
            show_help
            exit 1
            ;;
    esac
}

main "$@"
