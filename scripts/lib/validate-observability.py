#!/usr/bin/env python3
"""Structural assertions on the rendered observability chart.

Separate from helm-validate.sh because the checks need real YAML and JSON parsing,
and because inlining them as a heredoc inside a script that already nests heredocs
is how the quoting gets away from you.

Reads one rendered manifest path, prints one finding per line, exits 0 either way --
the caller decides what a finding means.
"""

from __future__ import annotations

import json
import os
import sys

import yaml


def main(path: str, repo_root: str) -> int:
    docs = [d for d in yaml.safe_load_all(open(path, encoding="utf-8")) if d]
    findings: list[str] = []

    def of_kind(kind: str) -> list[dict]:
        return [d for d in docs if d.get("kind") == kind]

    # 1. Default-deny must exist. Every other policy in the chart is an exception to it;
    #    without the deny they are decorative and every check below still passes.
    policies = of_kind("NetworkPolicy")
    denies = [
        p
        for p in policies
        if not (p["spec"].get("podSelector") or {}).get("matchLabels")
        and set(p["spec"].get("policyTypes", [])) == {"Ingress", "Egress"}
    ]
    if not denies:
        findings.append(
            "no default-deny NetworkPolicy: every other policy here is an exception to one that does not exist"
        )

    # 2. Prometheus needs 443 as well as 6443. In-cluster clients dial
    #    kubernetes.default.svc:443 and Calico evaluates policy pre-DNAT, so allowing only
    #    the node port blocks discovery while every manual check from a node shell works.
    api_ports: set[object] = set()
    for policy in policies:
        selector = json.dumps(policy["spec"].get("podSelector", {}))
        if "prometheus" not in selector:
            continue
        for rule in policy["spec"].get("egress", []):
            for port in rule.get("ports", []):
                api_ports.add(port.get("port"))
    for required in (443, 6443):
        if required not in api_ports:
            findings.append(
                f"prometheus egress does not allow port {required}: API-server discovery returns zero targets, silently"
            )

    # 3. The privileged workload must stay in the privileged namespace. A hostPath in the
    #    restricted namespace is refused by PSS at ReplicaSet level, which reports as a
    #    Deployment that exists and never produces a pod.
    for daemonset in of_kind("DaemonSet"):
        namespace = daemonset["metadata"].get("namespace", "")
        if not namespace.endswith("-agents"):
            findings.append(
                f"DaemonSet {daemonset['metadata']['name']} is in {namespace!r}, not the agents namespace"
            )

    # 4. A ConfigMap-only change leaves the pod template byte-identical, so Kubernetes has
    #    nothing to roll, helm reports success and every pod keeps the old config.
    for daemonset in of_kind("DaemonSet"):
        annotations = daemonset["spec"]["template"]["metadata"].get("annotations") or {}
        if not any(key.startswith("checksum/") for key in annotations):
            findings.append(
                f"DaemonSet {daemonset['metadata']['name']} has no checksum/ annotation: a config-only change rolls nothing"
            )

    # 5. Grafana logs a provisioning error for malformed JSON and then serves an empty
    #    folder, which is indistinguishable from having configured no dashboards at all.
    for config_map in of_kind("ConfigMap"):
        for key, body in (config_map.get("data") or {}).items():
            if not key.endswith(".json"):
                continue
            try:
                json.loads(body)
            except ValueError as exc:
                findings.append(f"{config_map['metadata']['name']}/{key} is not valid JSON: {exc}")

    # 6. An alert whose runbook annotation names a missing file is worse than an alert with
    #    no annotation, because it is followed under pressure.
    for config_map in of_kind("ConfigMap"):
        for key, body in (config_map.get("data") or {}).items():
            if not key.endswith((".yaml", ".yml")) or "groups:" not in body:
                continue
            for group in (yaml.safe_load(body) or {}).get("groups", []):
                for rule in group.get("rules", []):
                    name = rule.get("alert")
                    if not name:
                        continue
                    runbook = (rule.get("annotations") or {}).get("runbook")
                    if not runbook:
                        findings.append(f"alert {name} has no runbook annotation")
                    elif not os.path.isfile(os.path.join(repo_root, runbook)):
                        findings.append(f"alert {name} names {runbook}, which does not exist")

    sys.stdout.write("\n".join(findings))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1], sys.argv[2]))
