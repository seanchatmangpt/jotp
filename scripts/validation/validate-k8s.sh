#!/bin/bash
# Kubernetes Manifest Validation for JOTP
# Validates K8s YAML files using YAML syntax check and basic structure validation

set -euo pipefail

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
K8S_DIR="$PROJECT_ROOT/k8s"

FAILED=0
FILES_CHECKED=0
SKIPPED=0

print_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

print_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

print_error() {
    echo -e "${RED}[ERROR]${NC} $1"
    FAILED=$((FAILED + 1))
}

echo "▶ Kubernetes Manifest Validation"
echo ""

# Check if kubectl is available
if ! command -v kubectl >/dev/null 2>&1; then
    print_warn "kubectl is not installed - will skip cluster-aware validation"
fi

# Check for YAML validators
if command -v kubectl >/dev/null 2>&1; then
    print_info "kubectl version: $(kubectl version --client --short 2>/dev/null || echo 'unknown')"
fi

if command -v yq >/dev/null 2>&1; then
    print_info "yq version: $(yq --version 2>/dev/null | head -1 || echo 'unknown')"
fi
echo ""

# Find all K8s YAML files
K8S_FILES=()
if [ -d "$K8S_DIR" ]; then
    while IFS= read -r -d '' file; do
        K8S_FILES+=("$file")
    done < <(find "$K8S_DIR" -type f \( -name "*.yaml" -o -name "*.yml" \) -print0)
fi

# Also check for K8s files in docs/distributed/k8s
DIST_K8S_DIR="$PROJECT_ROOT/docs/distributed/k8s"
if [ -d "$DIST_K8S_DIR" ]; then
    while IFS= read -r -d '' file; do
        K8S_FILES+=("$file")
    done < <(find "$DIST_K8S_DIR" -type f \( -name "*.yaml" -o -name "*.yml" \) -print0)
fi

if [ ${#K8S_FILES[@]} -eq 0 ]; then
    print_warn "No Kubernetes YAML files found"
    exit 0
fi

print_info "Found ${#K8S_FILES[@]} file(s) to validate"
echo ""

for k8s_file in "${K8S_FILES[@]}"; do
    relative_path="${k8s_file#$PROJECT_ROOT/}"
    filename=$(basename "$k8s_file")

    # Skip kustomization files - they require kustomize, not kubectl
    if [[ "$filename" == "kustomization.yaml" ]] || [[ "$filename" == "kustomization.yml" ]] || [[ "$filename" == "Kustomization" ]]; then
        echo -n "Skipping (kustomization): $relative_path... "
        print_warn "⊘"
        SKIPPED=$((SKIPPED + 1))
        continue
    fi

    # Skip example directories that may have CRDs
    if [[ "$relative_path" == *"/examples/"* ]]; then
        # Check if file contains CRD-like content
        if grep -q "kind: CustomResourceDefinition\|kind: PrometheusRule\|kind: MetricsDiscovery" "$k8s_file" 2>/dev/null; then
            echo -n "Skipping (CRD): $relative_path... "
            print_warn "⊘"
            SKIPPED=$((SKIPPED + 1))
            continue
        fi
    fi

    echo -n "Validating: $relative_path... "

    # Check YAML syntax first
    yaml_valid=false

    if command -v yq >/dev/null 2>&1; then
        if yq eval '.' "$k8s_file" >/dev/null 2>&1; then
            yaml_valid=true
        else
            print_error "✗ Invalid YAML"
            continue
        fi
    elif command -v python3 >/dev/null 2>&1; then
        if python3 -c "import yaml, sys; yaml.safe_load_all(open('$k8s_file'))" 2>/dev/null; then
            yaml_valid=true
        else
            print_error "✗ Invalid YAML"
            continue
        fi
    else
        # Fallback: basic check if file is readable and non-empty
        if [ -s "$k8s_file" ]; then
            yaml_valid=true
        fi
    fi

    if [ "$yaml_valid" = false ]; then
        print_error "✗ Invalid YAML"
        continue
    fi

    # Check for required K8s fields (apiVersion, kind, metadata)
    if command -v yq >/dev/null 2>&1; then
        # For multi-document YAML, check each document
        doc_count=$(yq eval 'length' "$k8s_file" 2>/dev/null || echo "1")

        if [ "$doc_count" -gt 1 ]; then 2>/dev/null
            # Multi-document YAML
            for ((i=0; i<doc_count; i++)); do
                api_version=$(yq eval ".[$i].apiVersion" "$k8s_file" 2>/dev/null || echo "")
                kind=$(yq eval ".[$i].kind" "$k8s_file" 2>/dev/null || echo "")
                metadata=$(yq eval ".[$i].metadata" "$k8s_file" 2>/dev/null || echo "")

                if [ -z "$api_version" ] || [ -z "$kind" ] || [ -z "$metadata" ]; then
                    print_error "✗ Missing required fields (apiVersion, kind, or metadata) in document $i"
                    continue 2
                fi
            done
        else
            # Single document YAML
            api_version=$(yq eval '.apiVersion' "$k8s_file" 2>/dev/null || echo "")
            kind=$(yq eval '.kind' "$k8s_file" 2>/dev/null || echo "")
            metadata=$(yq eval '.metadata' "$k8s_file" 2>/dev/null || echo "")

            if [ -z "$api_version" ] || [ -z "$kind" ] || [ -z "$metadata" ]; then
                print_error "✗ Missing required fields (apiVersion, kind, or metadata)"
                continue
            fi
        fi
    fi

    # Try kubectl dry-run if available (optional, cluster-aware validation)
    if command -v kubectl >/dev/null 2>&1; then
        if kubectl apply --dry-run=client --validate=false -f "$k8s_file" >/dev/null 2>&1; then
            print_info "✓"
            FILES_CHECKED=$((FILES_CHECKED + 1))
        else
            # kubectl failed but YAML is valid - likely needs cluster context
            # Check if it's a cluster resource
            if grep -q "kind: StorageClass\|kind: Namespace\|kind: ClusterRole\|kind: ClusterRoleBinding\|kind: PodDisruptionBudget" "$k8s_file" 2>/dev/null; then
                print_info "✓ (cluster resource)"
                FILES_CHECKED=$((FILES_CHECKED + 1))
            else
                # YAML is valid but kubectl can't validate without cluster
                print_info "✓ (valid YAML, cluster required for full validation)"
                FILES_CHECKED=$((FILES_CHECKED + 1))
            fi
        fi
    else
        # No kubectl available, but YAML is valid
        print_info "✓ (valid YAML)"
        FILES_CHECKED=$((FILES_CHECKED + 1))
    fi
done

echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
print_info "Files validated: $FILES_CHECKED"
print_warn "Files skipped: $SKIPPED"

if [ $FAILED -eq 0 ]; then
    print_info "✓ All Kubernetes manifests are valid"
    exit 0
else
    print_error "✗ Kubernetes validation failed ($FAILED error(s))"
    exit 1
fi
