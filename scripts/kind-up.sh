#!/usr/bin/env bash
#
# Create the Tier L cluster: kind + Calico + ArgoCD, then hand off.
#
# Three rules, the same ones scripts/probe.sh follows:
#
#   1. Idempotent. Running it twice is a no-op, not an error. A bootstrap script
#      you are afraid to re-run is a script you will re-run by deleting the
#      cluster, which is how "it works on a clean cluster" becomes the only
#      supported path.
#   2. It waits for things, and says what it is waiting for. Most kind
#      bootstrap failures are races that present as unrelated errors thirty
#      seconds later.
#   3. It never silently degrades. If Calico cannot be installed, it stops --
#      because the alternative is a cluster where every NetworkPolicy in this
#      repo is inert and nothing says so.
set -euo pipefail

CLUSTER="${CLUSTER:-twitter-clone}"
CNI="${CNI:-calico}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

log()  { printf '\033[1;34m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m!!\033[0m  %s\n' "$*" >&2; }
die()  { printf '\033[1;31mxx\033[0m  %s\n' "$*" >&2; exit 1; }

need() { command -v "$1" >/dev/null 2>&1 || die "$1 is required but not on PATH"; }
need kind
need kubectl
need helm
need docker

# --- cluster ----------------------------------------------------------------

if kind get clusters 2>/dev/null | grep -qx "$CLUSTER"; then
  log "cluster '$CLUSTER' already exists, reusing it"
else
  log "creating cluster '$CLUSTER' (3 nodes, no default CNI)"
  kind create cluster --name "$CLUSTER" --config "$ROOT/deploy/kind/cluster.yaml" --wait 120s
fi

kubectl config use-context "kind-$CLUSTER" >/dev/null

# --- CNI --------------------------------------------------------------------
#
# Until this lands, every node is NotReady and every pod is Pending. That is the
# expected state of a cluster created with disableDefaultCNI, not a fault.

if kubectl get daemonset -n kube-system calico-node >/dev/null 2>&1; then
  log "Calico already installed"
elif [ "$CNI" = "kindnet" ]; then
  warn "CNI=kindnet: NetworkPolicy objects will be ACCEPTED AND IGNORED."
  warn "Every default-deny policy in deploy/charts will look applied and enforce nothing."
  kubectl apply -f https://raw.githubusercontent.com/kubernetes-sigs/kind/v0.25.0/pkg/build/nodeimage/default-cni.yaml
else
  log "installing Calico (this is what makes NetworkPolicy real)"
  kubectl apply --server-side -f https://raw.githubusercontent.com/projectcalico/calico/v3.28.2/manifests/tigera-operator.yaml \
    || die "could not install the Calico operator; refusing to continue with an unenforced CNI"
  kubectl wait --for=condition=Available --timeout=180s -n tigera-operator deployment/tigera-operator

  # The podSubnet here must match deploy/kind/cluster.yaml. If they diverge,
  # pods get addresses the kubelet does not route and every cross-node call
  # times out -- which reads exactly like a NetworkPolicy blocking traffic.
  kubectl apply -f - <<'YAML'
apiVersion: operator.tigera.io/v1
kind: Installation
metadata:
  name: default
spec:
  calicoNetwork:
    ipPools:
      - cidr: 10.244.0.0/16
        encapsulation: VXLANCrossSubnet
        natOutgoing: Enabled
YAML
fi

log "waiting for nodes to become Ready"
kubectl wait --for=condition=Ready nodes --all --timeout=300s

# --- ArgoCD -----------------------------------------------------------------

if kubectl get ns argocd >/dev/null 2>&1; then
  log "argocd namespace already exists, reusing it"
else
  log "installing ArgoCD"
  kubectl create namespace argocd
fi
kubectl apply -n argocd --server-side -f https://raw.githubusercontent.com/argoproj/argo-cd/v2.13.1/manifests/install.yaml

log "waiting for the ArgoCD server (first start pulls several images)"
kubectl wait --for=condition=Available --timeout=600s -n argocd deployment/argocd-server

# Reachable at http://localhost:8089 via the extraPortMapping. NodePort rather
# than a port-forward, because a port-forward dies with the shell that started it
# and the next command then fails for a reason unrelated to what it is doing.
kubectl patch svc argocd-server -n argocd --type merge -p \
  '{"spec":{"type":"NodePort","ports":[{"name":"http","port":80,"targetPort":8080,"nodePort":30081}]}}'

# --- GitOps or direct -------------------------------------------------------

REPO_URL="${REPO_URL:-$(git -C "$ROOT" remote get-url origin 2>/dev/null || true)}"

echo
if [ -n "$REPO_URL" ]; then
  log "bootstrapping the app-of-apps against $REPO_URL"
  sed "s|REPO_URL_PLACEHOLDER|$REPO_URL|g" "$ROOT/deploy/argocd/root.yaml" | kubectl apply -f -
  log "ArgoCD now owns the cluster. Watch it with: kubectl get app -n argocd -w"
else
  warn "no git remote and no REPO_URL -- skipping the ArgoCD bootstrap."
  warn "ArgoCD reads manifests from a git server; it cannot read this working"
  warn "directory. That is not a workaround to find, it is how GitOps works."
  echo
  log "for a working cluster without a remote, run:  make kind-deploy"
  log "which helm-installs the same charts directly. Same manifests, no GitOps."
fi

echo
log "cluster ready"
printf '     ArgoCD   http://localhost:8089  (admin / %s)\n' \
  "$(kubectl -n argocd get secret argocd-initial-admin-secret -o jsonpath='{.data.password}' 2>/dev/null | base64 -d 2>/dev/null || echo '<not created yet>')"
printf '     web      http://localhost:8088  (after a deploy)\n'
