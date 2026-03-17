#!/bin/bash
# Helm Chart Deployment Script for JOTP
# This script deploys JOTP to a Kubernetes cluster using Helm

set -euo pipefail

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Function to print colored output
print_info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

print_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

print_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

print_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# Function to check if a command exists
command_exists() {
    command -v "$1" >/dev/null 2>&1
}

# Function to prompt for confirmation
confirm() {
    local prompt="$1"
    local default="${2:-n}"

    if [[ "$default" == "y" ]]; then
        prompt="$prompt [Y/n]"
    else
        prompt="$prompt [y/N]"
    fi

    while true; do
        read -rp "$prompt" response
        response=${response:-$default}

        case "$response" in
            [Yy]|[Yy][Ee][Ss])
                return 0
                ;;
            [Nn]|[Nn][Oo])
                return 1
                ;;
            *)
                echo "Please answer yes or no."
                ;;
        esac
    done
}

# Function to validate cluster connectivity
validate_cluster() {
    print_info "Validating cluster connectivity..."

    if ! command_exists kubectl; then
        print_error "kubectl is not installed. Please install kubectl to continue."
        exit 1
    fi

    if ! kubectl cluster-info >/dev/null 2>&1; then
        print_error "Cannot connect to Kubernetes cluster. Please check your kubeconfig."
        exit 1
    fi

    print_success "✓ Cluster connectivity validated"
}

# Function to generate secure cookie
generate_cookie() {
    if command_exists openssl; then
        openssl rand -base64 32
    else
        # Fallback to random string
        cat /dev/urandom | LC_ALL=C tr -dc 'a-zA-Z0-9' | fold -w 32 | head -n 1
    fi
}

# Function to create namespace
create_namespace() {
    local namespace=$1

    print_info "Creating namespace: $namespace"

    if kubectl get namespace "$namespace" >/dev/null 2>&1; then
        print_warn "⚠ Namespace $namespace already exists"
    else
        kubectl create namespace "$namespace"
        print_success "✓ Namespace $namespace created"
    fi
}

# Function to create secret
create_secret() {
    local namespace=$1
    local secret_name=$2
    local cookie=${3:-$(generate_cookie)}

    print_info "Creating secret: $secret_name"

    if kubectl get secret "$secret_name" -n "$namespace" >/dev/null 2>&1; then
        print_warn "⚠ Secret $secret_name already exists"
    else
        kubectl create secret generic "$secret_name" \
            --from-literal=cookie="$cookie" \
            -n "$namespace"
        print_success "✓ Secret $secret_name created"
        print_info "🔑 Generated cookie: $cookie"
    fi
}

# Function to deploy JOTP
deploy_jotp() {
    local release_name=$1
    local namespace=$2
    local chart_dir=$3
    local values_file=$4
    local cookie=${5:-}

    print_info "Deploying JOTP..."

    local helm_args=(
        "$release_name"
        "$chart_dir"
        --namespace "$namespace"
        --create-namespace
        --timeout 10m
        --wait
    )

    if [[ -n "$values_file" ]]; then
        helm_args+=(-f "$values_file")
    fi

    if [[ -n "$cookie" ]]; then
        helm_args+=(--set jotp.cookie="$cookie")
    fi

    if helm upgrade --install "${helm_args[@]}"; then
        print_success "✓ JOTP deployed successfully"
        return 0
    else
        print_error "✗ JOTP deployment failed"
        return 1
    fi
}

# Function to verify deployment
verify_deployment() {
    local release_name=$1
    local namespace=$2

    print_info "Verifying deployment..."

    # Wait for pods to be ready
    print_info "Waiting for pods to be ready..."
    if kubectl wait --for=condition=ready pod \
        -l "app.kubernetes.io/instance=$release_name" \
        -n "$namespace" \
        --timeout=300s; then
        print_success "✓ All pods are ready"
    else
        print_warn "⚠ Some pods are not ready yet"
    fi

    # Show deployment status
    echo ""
    print_info "Deployment status:"
    helm status "$release_name" -n "$namespace"

    # Show pods
    echo ""
    print_info "Pods:"
    kubectl get pods -n "$namespace" -l "app.kubernetes.io/instance=$release_name"

    # Show services
    echo ""
    print_info "Services:"
    kubectl get svc -n "$namespace" -l "app.kubernetes.io/instance=$release_name"
}

# Function to print post-deployment instructions
print_instructions() {
    local release_name=$1
    local namespace=$2

    echo ""
    print_success "========================================="
    print_success "Deployment completed successfully!"
    print_success "========================================="
    echo ""

    print_info "Next steps:"
    echo ""
    echo "  1. View deployment status:"
    echo "     helm status $release_name -n $namespace"
    echo ""
    echo "  2. View pod logs:"
    echo "     kubectl logs -n $namespace -l app.kubernetes.io/instance=$release_name -f"
    echo ""
    echo "  3. Port forward to access the application:"
    echo "     kubectl port-forward -n $namespace svc/$release_name 8080:8080"
    echo ""
    echo "  4. Test health endpoint:"
    echo "     curl http://localhost:8080/health/ready"
    echo ""
    echo "  5. Uninstall when done:"
    echo "     helm uninstall $release_name -n $namespace"
    echo ""
}

# Main deployment function
main() {
    local chart_dir="${1:-./helm/jotp}"
    local release_name="${2:-jotp}"
    local namespace="${3:-jotp}"
    local environment="${4:-dev}"
    local cookie=""

    print_info "========================================="
    print_info "JOTP Helm Deployment"
    print_info "========================================="
    echo ""

    # Validate cluster connectivity
    validate_cluster
    echo ""

    # Determine values file based on environment
    local values_file=""
    case "$environment" in
        dev)
            values_file="$chart_dir/values-dev.yaml"
            ;;
        staging)
            values_file="$chart_dir/values-staging.yaml"
            ;;
        prod)
            values_file="$chart_dir/values-prod.yaml"
            # Generate secure cookie for production
            cookie=$(generate_cookie)
            print_info "🔑 Generated secure production cookie"
            ;;
        *)
            print_warn "Unknown environment: $environment, using default values"
            ;;
    esac

    print_info "Configuration:"
    echo "  Chart directory: $chart_dir"
    echo "  Release name: $release_name"
    echo "  Namespace: $namespace"
    echo "  Environment: $environment"
    if [[ -n "$values_file" ]]; then
        echo "  Values file: $values_file"
    fi
    echo ""

    # Confirm deployment
    if ! confirm "Deploy JOTP with these settings?"; then
        print_info "Deployment cancelled"
        exit 0
    fi

    echo ""

    # Create namespace
    create_namespace "$namespace"
    echo ""

    # Create secret for cookie
    if [[ -n "$cookie" ]]; then
        create_secret "$namespace" "${release_name}-cookie" "$cookie"
        echo ""
    fi

    # Deploy JOTP
    if deploy_jotp "$release_name" "$namespace" "$chart_dir" "$values_file" "$cookie"; then
        echo ""
        verify_deployment "$release_name" "$namespace"
        echo ""
        print_instructions "$release_name" "$namespace"
    else
        print_error "Deployment failed. Please check the logs above for details."
        exit 1
    fi
}

# Show usage
usage() {
    cat <<EOF
Usage: $0 [CHART_DIR] [RELEASE_NAME] [NAMESPACE] [ENVIRONMENT]

Arguments:
  CHART_DIR       Path to Helm chart directory (default: ./helm/jotp)
  RELEASE_NAME    Helm release name (default: jotp)
  NAMESPACE       Kubernetes namespace (default: jotp)
  ENVIRONMENT     Environment: dev, staging, prod (default: dev)

Examples:
  # Deploy to development environment
  $0

  # Deploy to staging environment
  $0 ./helm/jotp jotp-staging jotp-staging staging

  # Deploy to production environment
  $0 ./helm/jotp jotp-prod jotp-prod prod

EOF
}

# Parse arguments
if [[ "${1:-}" == "-h" ]] || [[ "${1:-}" == "--help" ]]; then
    usage
    exit 0
fi

# Run main function
main "$@"
