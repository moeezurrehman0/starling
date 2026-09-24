# SPDX-License-Identifier: MIT
"""Deterministic risk analysis of a `terraform plan` JSON file.

The division of labour in this package is deliberate and is the whole design:

    this module decides what is risky.  The LLM only writes the prose.

An LLM asked "is this Terraform plan dangerous?" will answer confidently either
way, and will answer differently on Tuesday. That is not a control. Worse, it
fails in the direction this project keeps finding: toward reassurance. Every
judgement that a human might act on -- this destroys a table, this drops
deletion protection, this opens a security group to the internet -- is made
here, in code, with fixtures and a self-test. The model is handed the findings
and asked to explain them to a reviewer who does not read HCL.

So the commenter degrades to "correct but terse" when no model is configured,
never to "silently absent" and never to "confidently wrong".
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field

# Resource types whose destruction loses data that no `terraform apply` can put
# back. A replace of one of these is not a deploy, it is a restore-from-backup
# exercise that nobody has scheduled.
STATEFUL = {
    "aws_db_instance",
    "aws_dynamodb_table",
    "aws_ebs_volume",
    "aws_efs_file_system",
    "aws_elasticache_cluster",
    "aws_elasticache_replication_group",
    "aws_opensearch_domain",
    "aws_rds_cluster",
    "aws_rds_cluster_instance",
    "aws_s3_bucket",
}

# Destroying these does not lose data, but it does drop traffic: the resource is
# gone for the window between destroy and create, and for anything fronting a
# DNS name that window is extended by whatever the TTL is.
DISRUPTIVE = {
    "aws_autoscaling_group",
    "aws_cloudfront_distribution",
    "aws_db_subnet_group",
    "aws_ecs_service",
    "aws_eks_cluster",
    "aws_eks_node_group",
    "aws_lb",
    "aws_lb_listener",
    "aws_lb_target_group",
    "aws_nat_gateway",
    "aws_route53_record",
}

# Changes here alter who can do what. They are not disruptive and they are not
# lossy, which is exactly why they slide through review unremarked.
SECURITY = {
    "aws_iam_group_policy",
    "aws_iam_policy",
    "aws_iam_role",
    "aws_iam_role_policy",
    "aws_iam_role_policy_attachment",
    "aws_iam_user",
    "aws_iam_user_policy",
    "aws_network_acl_rule",
    "aws_s3_bucket_policy",
    "aws_s3_bucket_public_access_block",
    "aws_security_group",
    "aws_security_group_rule",
    "aws_vpc_security_group_egress_rule",
    "aws_vpc_security_group_ingress_rule",
}

# Attributes that exist specifically to stop an accident. Turning one off is
# nearly always a step taken to make some *other* change apply cleanly, which
# means the reviewer is being asked to approve the guardrail removal as a side
# effect of something else.
GUARDRAILS = {
    "deletion_protection": True,
    "deletion_protection_enabled": True,
    "enable_deletion_protection": True,
    "force_destroy": False,
    "skip_final_snapshot": False,
    "point_in_time_recovery": True,
}

# Severity ordering. Strings rather than an enum so the JSON output is readable
# without a decoder ring; the ranking lives in one place.
SEVERITIES = ["critical", "high", "medium", "low", "info"]


def _rank(sev: str) -> int:
    return SEVERITIES.index(sev) if sev in SEVERITIES else len(SEVERITIES)


@dataclass(frozen=True)
class Finding:
    """One thing a reviewer should look at, with the evidence attached.

    `evidence` is always a concrete address or attribute path. A finding that
    cannot point at the line it is talking about is an opinion.
    """

    severity: str
    kind: str
    address: str
    summary: str
    evidence: str = ""


@dataclass
class Report:
    findings: list[Finding] = field(default_factory=list)
    counts: dict[str, int] = field(default_factory=dict)
    cost: dict[str, object] | None = None
    # Security resources created (not modified) with nothing wrong in their
    # policy document. Reported as one line, never as one finding each.
    security_creates: int = 0

    @property
    def worst(self) -> str:
        """The highest severity present, or `info` for an empty report.

        Used to decide whether the comment leads with a warning. Defaults to the
        *least* alarming value so that a plan with no findings cannot be
        rendered as though it had one.
        """
        return min((f.severity for f in self.findings), key=_rank, default="info")

    def sorted_findings(self) -> list[Finding]:
        return sorted(self.findings, key=lambda f: (_rank(f.severity), f.address))

    def to_dict(self) -> dict:
        return {
            "worst": self.worst,
            "counts": self.counts,
            "cost": self.cost,
            "security_creates": self.security_creates,
            "findings": [vars(f) for f in self.sorted_findings()],
        }


def _actions(change: dict) -> list[str]:
    return list(change.get("actions") or [])


def _is_replace(actions: list[str]) -> bool:
    # Terraform expresses a replacement as both verbs in one change. The order
    # differs -- ["delete","create"] normally, ["create","delete"] under
    # create_before_destroy -- and matching on only one order silently misses
    # every resource with a lifecycle block, which is disproportionately the
    # important ones.
    return "create" in actions and "delete" in actions


def _replace_paths(change: dict) -> str:
    paths = change.get("replace_paths") or []
    rendered = [".".join(str(p) for p in path) for path in paths]
    return ", ".join(rendered)


def _guardrail_findings(addr: str, change: dict) -> list[Finding]:
    """Detect a protective attribute being moved to its unsafe value."""
    out: list[Finding] = []
    before = change.get("before") or {}
    after = change.get("after") or {}
    if not isinstance(before, dict) or not isinstance(after, dict):
        return out

    for attr, safe in GUARDRAILS.items():
        if attr not in after:
            continue
        old, new = before.get(attr), after.get(attr)

        # point_in_time_recovery is a block, not a bool, on DynamoDB. Reading
        # `.enabled` out of it keeps one rule covering both shapes rather than
        # having a second near-identical rule that drifts.
        if isinstance(new, list) and new and isinstance(new[0], dict):
            new = new[0].get("enabled")
            old = old[0].get("enabled") if isinstance(old, list) and old else old
        if isinstance(new, dict):
            new = new.get("enabled")
            old = old.get("enabled") if isinstance(old, dict) else old

        if new is None or new == safe or old == new:
            continue
        # json.dumps, not f-string: Python renders booleans as `True`, and a
        # comment about Terraform that says `True` reads as though the tool does
        # not know what language it is looking at.
        out.append(
            Finding(
                severity="critical",
                kind="guardrail-removed",
                address=addr,
                summary=(
                    f"`{attr}` changes from "
                    f"`{json.dumps(old)}` to `{json.dumps(new)}`"
                ),
                evidence=(
                    "This attribute exists to prevent an accidental destroy. "
                    "Removing it is usually a prerequisite for some other "
                    "change, so approve it on its own merits."
                ),
            )
        )
    return out


def _open_cidr_findings(addr: str, rtype: str, change: dict) -> list[Finding]:
    """Flag a rule that admits the whole internet.

    Scoped to ingress: an egress rule to 0.0.0.0/0 is the default posture for
    most workloads and flagging it would train reviewers to skip the section.
    """
    if rtype not in {
        "aws_security_group",
        "aws_security_group_rule",
        "aws_vpc_security_group_ingress_rule",
    }:
        return []
    after = change.get("after") or {}
    if not isinstance(after, dict):
        return []
    actions = _actions(change)
    if "delete" in actions and "create" not in actions:
        return []

    blocks: list[dict] = []
    if rtype == "aws_security_group":
        blocks = [b for b in (after.get("ingress") or []) if isinstance(b, dict)]
    elif rtype == "aws_security_group_rule":
        if after.get("type") == "ingress":
            blocks = [after]
    else:
        blocks = [after]

    out: list[Finding] = []
    for b in blocks:
        cidrs = b.get("cidr_blocks") or b.get("cidr_ipv4") or []
        if isinstance(cidrs, str):
            cidrs = [cidrs]
        if "0.0.0.0/0" not in cidrs:
            continue
        port = b.get("from_port")
        suffix = f" on port `{port}`" if port is not None else ""
        out.append(
            Finding(
                severity="high",
                kind="open-ingress",
                address=addr,
                summary=f"ingress from `0.0.0.0/0`{suffix}",
                evidence="Reachable from the public internet, not only from the VPC.",
            )
        )
    return out


_POLICY_KEYS = ("policy", "assume_role_policy", "policy_document")


def _statements(doc: object) -> list[dict]:
    """Parse an IAM policy document into a list of statements.

    The attribute is a JSON *string* in the plan, and on a create with an
    unknown interpolation it may be absent entirely. Both cases return [] --
    silence rather than a crash, because this function runs over every
    security resource in the plan.
    """
    if isinstance(doc, str):
        try:
            doc = json.loads(doc)
        except (ValueError, TypeError):
            return []
    if not isinstance(doc, dict):
        return []
    stmts = doc.get("Statement")
    if isinstance(stmts, dict):
        return [stmts]
    return [s for s in (stmts or []) if isinstance(s, dict)]


def _as_list(v: object) -> list:
    if v is None:
        return []
    return v if isinstance(v, list) else [v]


def _policy_findings(addr: str, rtype: str, change: dict) -> list[Finding]:
    """Flag IAM policy documents that grant more than they should.

    This is the content check that lets the create-noise suppression above be
    safe. Without it, tightening "every IAM resource is a finding" down to
    "only modified IAM resources are findings" would mean a brand-new
    `Action: "*"` policy sailed through a greenfield plan unmentioned.
    """
    after = change.get("after") or {}
    if not isinstance(after, dict):
        return []

    out: list[Finding] = []
    for key in _POLICY_KEYS:
        for stmt in _statements(after.get(key)):
            if stmt.get("Effect") not in (None, "Allow"):
                continue

            principal = stmt.get("Principal")
            flat = []
            if isinstance(principal, dict):
                for v in principal.values():
                    flat.extend(_as_list(v))
            else:
                flat = _as_list(principal)
            if "*" in flat:
                out.append(
                    Finding(
                        severity="high",
                        kind="public-principal",
                        address=addr,
                        summary=f"`{rtype}` grants access to principal `*`",
                        evidence="Any AWS account can assume or use this.",
                    )
                )

            actions = [a for a in _as_list(stmt.get("Action")) if isinstance(a, str)]
            wild = [a for a in actions if a == "*" or a.endswith(":*")]
            if wild:
                out.append(
                    Finding(
                        severity="high",
                        kind="wildcard-action",
                        address=addr,
                        summary=f"`{rtype}` allows {', '.join(sorted(set(wild)))}",
                        evidence="Least privilege requires enumerated actions.",
                    )
                )
            elif "*" in _as_list(stmt.get("Resource")) and actions:
                out.append(
                    Finding(
                        severity="medium",
                        kind="wildcard-resource",
                        address=addr,
                        summary=f"`{rtype}` allows {len(actions)} action(s) on `*`",
                        evidence="Scope to specific ARNs where the API supports it.",
                    )
                )
    return out


def analyse_plan(plan: dict) -> Report:
    """Turn `terraform show -json` output into a Report.

    Reads `resource_changes`, which is the only part of the plan file that
    describes *actions*. `planned_values` describes the desired end state and
    looks like a reasonable thing to read instead; it cannot distinguish a
    resource that is being replaced from one that is being left alone, so a
    checker built on it is blind to the single most dangerous class of change.
    """
    report = Report()
    counts = {"create": 0, "update": 0, "delete": 0, "replace": 0, "no-op": 0}

    for rc in plan.get("resource_changes") or []:
        # Data sources appear here with a `read` action and no risk attached.
        if rc.get("mode") != "managed":
            continue
        addr = rc.get("address", "<unknown>")
        rtype = rc.get("type", "")
        change = rc.get("change") or {}
        actions = _actions(change)

        if actions == ["no-op"]:
            counts["no-op"] += 1
            continue

        replacing = _is_replace(actions)
        if replacing:
            counts["replace"] += 1
        elif actions == ["create"]:
            counts["create"] += 1
        elif actions == ["update"]:
            counts["update"] += 1
        elif actions == ["delete"]:
            counts["delete"] += 1

        destroys = replacing or actions == ["delete"]
        creates_only = actions == ["create"]
        verb = "replaced" if replacing else "destroyed"

        if destroys and rtype in STATEFUL:
            why = _replace_paths(change)
            reason = rc.get("action_reason") or ""
            report.findings.append(
                Finding(
                    severity="critical",
                    kind="data-loss",
                    address=addr,
                    summary=(
                        f"{verb} — `{rtype}` holds data that apply cannot restore"
                    ),
                    evidence=(
                        f"forced by: {why}"
                        if why
                        else (reason or "scheduled for destruction")
                    ),
                )
            )
        elif destroys and rtype in DISRUPTIVE:
            why = _replace_paths(change)
            report.findings.append(
                Finding(
                    severity="high",
                    kind="downtime",
                    address=addr,
                    summary=f"{verb} — `{rtype}` serves traffic",
                    evidence=f"forced by: {why}" if why else "expect a gap in service",
                )
            )
        elif destroys:
            report.findings.append(
                Finding(
                    severity="medium",
                    kind="destroy",
                    address=addr,
                    summary=verb,
                    evidence=_replace_paths(change),
                )
            )

        if rtype in SECURITY and not destroys:
            policy = _policy_findings(addr, rtype, change)
            report.findings.extend(policy)
            # A *modified* security resource is worth a reviewer's eye even when
            # the content looks benign, because the risk is in the delta. A
            # *created* one is not: on a greenfield plan every IAM role in the
            # design is a create, and flagging all of them produced 25 identical
            # mediums on the prod root -- the exact noise that trains a reviewer
            # to scroll past the section where the one real finding lives.
            if not policy and creates_only is False:
                report.findings.append(
                    Finding(
                        severity="medium",
                        kind="permissions",
                        address=addr,
                        summary=f"`{rtype}` changes who can do what",
                        evidence="review the effective permissions, not just the diff",
                    )
                )
            elif not policy:
                report.security_creates += 1

        report.findings.extend(_guardrail_findings(addr, change))
        report.findings.extend(_open_cidr_findings(addr, rtype, change))

    report.counts = counts
    return report


def add_checkov(report: Report, checkov: dict) -> Report:
    """Fold Checkov's failed checks in as findings.

    Only failures, and only with the file and line attached. Checkov's passed
    checks run to thousands of lines and would bury everything above.
    """
    results = (checkov or {}).get("results") or {}
    for c in results.get("failed_checks") or []:
        sev = (c.get("severity") or "medium").lower()
        if sev not in SEVERITIES:
            sev = "medium"
        path = c.get("file_path", "")
        lines = c.get("file_line_range") or []
        where = f"{path}:{lines[0]}" if path and lines else path
        report.findings.append(
            Finding(
                severity=sev,
                kind="policy",
                address=c.get("resource") or c.get("check_id", "checkov"),
                summary=c.get("check_name", c.get("check_id", "policy failure")),
                evidence=f"{c.get('check_id', '')} {where}".strip(),
            )
        )
    return report


def add_infracost(report: Report, infracost: dict) -> Report:
    """Attach the monthly cost delta, and flag a large one.

    A cost increase is not a defect, so nothing is raised unless it is big
    enough that somebody should have been told before the pull request. The
    threshold is deliberately absolute rather than a percentage: a 400% rise on
    a $2 footprint is noise, and a 15% rise on a $4000 one is not.
    """
    if not infracost:
        return report
    try:
        diff = float(infracost.get("diffTotalMonthlyCost") or 0.0)
        total = float(infracost.get("totalMonthlyCost") or 0.0)
    except (TypeError, ValueError):
        return report

    currency = infracost.get("currency") or "USD"
    report.cost = {"diff": diff, "total": total, "currency": currency}
    if diff >= 50.0:
        report.findings.append(
            Finding(
                severity="medium",
                kind="cost",
                address="(plan)",
                summary=f"monthly cost rises by {diff:.2f} {currency}",
                evidence=f"new estimated total {total:.2f} {currency}/month",
            )
        )
    return report


def load(path: str | None) -> dict:
    """Read a JSON file, treating absent and empty as an empty document.

    A missing Infracost file means the key was not configured, which is already
    reported elsewhere; making it fatal here would let an unrelated missing
    secret block the risk comment entirely.
    """
    if not path:
        return {}
    try:
        with open(path, encoding="utf-8") as fh:
            text = fh.read().strip()
    except FileNotFoundError:
        return {}
    if not text:
        return {}
    return json.loads(text)
