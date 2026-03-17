#!/bin/bash
set -euo pipefail

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Configuration
NAMESPACE=${NAMESPACE:-default}
REPLICAS=${REPLICAS:-3}
IMAGE=${IMAGE:-jotp:1.0}

echo -e "${GREEN}JOTP Kubernetes Deployment Script${NC}"
echo "======================================"
echo "Namespace: ${NAMESPACE}"
echo "Replicas: ${REPLICAS}"
echo "Image: ${IMAGE}"
echo ""

# Function to check if kubectl is available
check_kubectl() {
    if ! command -v kubectl &> /dev/null; then
        echo -e "${RED}Error: kubectl is not installed or not in PATH${NC}"
        exit 1
    fi
    echo -e "${GREEN}✓ kubectl found${NC}"
}

# Function to check if cluster is accessible
check_cluster() {
    if ! kubectl cluster-info &> /dev/null; then
        echo -e "${RED}Error: Cannot connect to Kubernetes cluster${NC}"
        exit 1
    fi
    echo -e "${GREEN}✓ Cluster accessible${NC}"
}

# Function to check if namespace exists
check_namespace() {
    if ! kubectl get namespace "${NAMESPACE}" &> /dev/null; then
        echo -e "${YELLOW}Creating namespace: ${NAMESPACE}${NC}"
        kubectl create namespace "${NAMESPACE}"
    fi
    echo -e "${GREEN}✓ Namespace ready: ${NAMESPACE}${NC}"
}

# Function to check storage class
check_storage_class() {
    if ! kubectl get storageclass fast-ssd &> /dev/null; then
        echo -e "${YELLOW}Warning: StorageClass 'fast-ssd' not found${NC}"
        echo "Using default storage class instead"
        # Update statefulset to use default storage class
        sed -i.bak 's/storageClassName: fast-ssd/# storageClassName: # Use default/' statefulset.yaml
    else
        echo -e "${GREEN}✓ StorageClass 'fast-ssd' found${NC}"
    fi
}

# Function to deploy resources
deploy_resources() {
    echo -e "${GREEN}Deploying JOTP resources...${NC}"

    # Create namespace if it doesn't exist
    kubectl create namespace "${NAMESPACE}" --dry-run=client -o yaml | kubectl apply -f -

    # Apply all manifests
    kubectl apply -f serviceaccount.yaml -n "${NAMESPACE}"
    kubectl apply -f configmap.yaml -n "${NAMESPACE}"
    kubectl apply -f service.yaml -n "${NAMESPACE}"
    kubectl apply -f statefulset.yaml -n "${NAMESPACE}"

    echo -e "${GREEN}✓ Resources deployed${NC}"
}

# Function to wait for pods to be ready
wait_for_pods() {
    echo -e "${YELLOW}Waiting for pods to be ready...${NC}"
    kubectl wait --for=condition=ready pod -l app=jotp -n "${NAMESPACE}" --timeout=300s

    echo -e "${GREEN}✓ All pods ready${NC}"
}

# Function to display deployment status
show_status() {
    echo ""
    echo -e "${GREEN}Deployment Status${NC}"
    echo "=================="
    echo ""

    echo "StatefulSet:"
    kubectl get statefulset jotp -n "${NAMESPACE}"
    echo ""

    echo "Pods:"
    kubectl get pods -l app=jotp -n "${NAMESPACE}"
    echo ""

    echo "Services:"
    kubectl get svc -l app=jotp -n "${NAMESPACE}"
    echo ""

    echo "ConfigMaps:"
    kubectl get configmap -l app=jotp -n "${NAMESPACE}"
}

# Function to display pod logs
show_logs() {
    echo ""
    echo -e "${GREEN}Recent Logs (jotp-0)${NC}"
    echo "========================"
    kubectl logs jotp-0 -n "${NAMESPACE}" --tail=20
}

# Function to run health checks
health_check() {
    echo ""
    echo -e "${GREEN}Running Health Checks${NC}"
    echo "======================="

    # Wait for startup probe to succeed
    echo "Waiting for startup probe..."
    kubectl wait --for=condition=ready pod -l app=jotp -n "${NAMESPACE}" --timeout=300s

    # Check liveness
    echo "Checking liveness..."
    kubectl exec jotp-0 -n "${NAMESPACE}" -- curl -s http://localhost:9091/actuator/health/liveness || echo "Liveness check failed"

    # Check readiness
    echo "Checking readiness..."
    kubectl exec jotp-0 -n "${NAMESPACE}" -- curl -s http://localhost:9091/actuator/health/readiness || echo "Readiness check failed"

    # Check peer connectivity
    echo "Checking peer connectivity..."
    kubectl exec jotp-0 -n "${NAMESPACE}" -- nslookup jotp-headless.${NAMESPACE}.svc.cluster.local

    echo -e "${GREEN}✓ Health checks completed${NC}"
}

# Main execution
main() {
    check_kubectl
    check_cluster
    check_namespace
    check_storage_class
    deploy_resources
    wait_for_pods
    show_status
    health_check
    show_logs

    echo ""
    echo -e "${GREEN}Deployment Complete!${NC}"
    echo ""
    echo "Next steps:"
    echo "1. Check logs: kubectl logs -f statefulset/jotp -n ${NAMESPACE}"
    echo "2. Check metrics: kubectl port-forward svc/jotp-monitoring 9090:9090 -n ${NAMESPACE}"
    echo "3. Access application: kubectl port-forward svc/jotp 8080:8080 -n ${NAMESPACE}"
    echo "4. Scale cluster: kubectl scale statefulset jotp --replicas=5 -n ${NAMESPACE}"
    echo ""
}

# Run main function
main "$@"
