# AIOps: a risk commenter and a triage agent

Two agents live in this repository. Neither of them decides anything.

That sentence is the whole design. The rest of this document explains why it is the only
version of an LLM-in-the-pipeline that survives contact with Phase 11, and what had to be
built to make it true rather than merely claimed.

---

## The thing Phase 11 taught, applied here

Phase 11 built a canary gate and then spent considerably longer proving it worked. Eight
defects turned up, and **every single one made the gate fail open** — report success while
a build with a measured 51.6% error rate was promoted. Not one failed closed. That
asymmetry is not bad luck. A check that is broken usually produces *no signal*, and code
that reads no signal as "nothing is wrong" is the default thing a person writes.

Now consider asking a language model "is this Terraform plan dangerous?"

A model answers that question confidently in both directions, differently on Tuesday than
on Monday, and — because it is trained to be helpful and agreeable — **it fails toward
reassurance**. It is a fail-open gate with a personality. Putting one on the risk path
would reintroduce, at the review step, the exact failure class the previous phase spent a
day eliminating at the deploy step.

So the split is:

| | Decides severity | Writes prose |
|---|---|---|
| `tools/aiops/risk.py` | ✅ | ❌ |
| the language model | ❌ | ✅ |

The model is shown the **already-ranked findings as JSON**. It never sees the plan file,
the state, the credentials, or the source tree. It cannot add a finding, remove one, or
change a severity, because by the time it is called those are already rendered into the
Markdown table and the model's output is appended as a separate prose section.

`AIOPS_PROVIDER` defaults to `none`, in which case the comment renders straight from the
template. A provider that errors falls back to the same template. In both cases the
comment **says which happened**, in the footer, every time — because a template-rendered
comment that looked model-written would be a small lie that makes the tool untrustworthy
in exactly the situation where you need to trust it.

---

## What `risk.py` actually checks

Four resource classifications and one attribute map:

- **`STATEFUL`** — destroy or replace means data that `apply` cannot bring back.
  Severity `critical`, kind `data-loss`.
- **`DISRUPTIVE`** — destroy or replace means a gap in service. `high`, `downtime`.
- **`SECURITY`** — changes who can do what. Content-dependent; see below.
- **`GUARDRAILS`** — an attribute → safe-value map. `deletion_protection`,
  `point_in_time_recovery`, `force_destroy`, public-access blocks. Flipping one away from
  its safe value is `critical`, kind `guardrail-removed`.

Plus open ingress from `0.0.0.0/0` (`high`), Checkov failures folded in at their own
severity, and an Infracost delta above an absolute threshold.

### Four details that are load-bearing

**Replacement detection must be order-insensitive.** A replacement is
`"create" in actions and "delete" in actions` — *not* `actions == ["delete", "create"]`.
Terraform emits the reverse order for anything with `create_before_destroy`, so matching a
single order silently exempts every resource with a lifecycle block. Those are
disproportionately the load-bearing ones.

**Data sources must be filtered out.** `rc.get("mode") != "managed"` is checked before
anything else. A `data "aws_security_group"` has a type in the `SECURITY` set and
attributes that look exactly like a managed resource's, so without the filter the tool
reports a permissions change and an open-ingress finding for infrastructure the plan does
not touch. The safe fixture contains one admitting `0.0.0.0/0` specifically so that
removing the filter breaks the test suite.

**Read `resource_changes`, never `planned_values`.** `planned_values` describes the
desired end state and looks like the reasonable thing to parse. It cannot distinguish a
resource being replaced from one being left alone, which makes a checker built on it blind
to the single most dangerous class of change.

**The cost threshold is absolute, not proportional.** $50/month. A 400% rise on a $2
resource is noise; a 15% rise on a $4,000 one is a conversation.

---

## The noise problem, which is the same problem wearing a different hat

The first run against the real production root produced a correct report that was
**useless**: 25 identical `medium` findings, one per IAM resource, each saying
`aws_iam_role changes who can do what`.

Every one was true. The prod root is greenfield, so every IAM role in the design is a
create, and creating an IAM role does indeed change who can do what.

A reviewer reads that table once, learns that the section is boilerplate, and collapses it
forever. At which point the tool has a 0% detection rate while reporting perfect coverage
— **a fail-open gate reached by a different route**. Phase 11's gates failed open by
measuring nothing. This one would fail open by measuring everything.

The fix is a content check rather than a type check. For a security resource that is being
*created*, the tool parses the policy document and reports only:

| Finding | Severity | Trigger |
|---|---|---|
| `public-principal` | high | `Principal` resolves to `*` |
| `wildcard-action` | high | an `Action` is `*` or ends in `:*` |
| `wildcard-resource` | medium | enumerated actions on `Resource: "*"` |

A security resource that is being *modified* still gets the generic finding, because there
the risk is in the delta and a human should look. A created resource with a clean policy
is **counted, not listed** — the comment ends with "23 security resource(s) are created
with no wildcard action, wildcard principal or open ingress", so the suppression is
disclosed rather than silent.

Result on the same plan: **25 findings → 2**.

### Both survivors are real

The two that remain are `dynamodb:ListStreams` on `Resource: "*"` in the `fanout-worker`
and `tweet-indexer` IRSA policies.

That is a true positive and a **won't-fix**: `ListStreams` does not support resource-level
permissions in IAM, so there is no ARN to scope it to. The correct response is a documented
exception, not a code change — and a tool that surfaces two findings worth arguing about is
doing its job, where one that surfaced twenty-five was not.

---

## Where the plan comes from

A risk commenter that runs "when someone has a playground session open" comments
approximately never, and arrives after review rather than during it.

`terraform validate` needs no credentials but produces no plan, and the plan is the only
artifact that distinguishes a replacement from an update. So the plan has to be real, and
it has to be producible on a runner with no AWS account. Two obstacles:

1. **The provider validates credentials at configure time.** Solved by
   `skip_credentials_validation`, `skip_requesting_account_id`, `skip_metadata_api_check`
   and `skip_region_validation` — four arguments that exist for precisely this.
2. **`data "aws_caller_identity"` is a real API call** that no flag can skip, because the
   account id genuinely appears in the rendered IAM policy documents. Something has to
   answer it. A LocalStack container does, via `endpoints { sts, iam, ec2 }`.

`scripts/tf-plan-mock.sh` copies `infra/terraform/ci/mock_override.tf` into the root as
`zz_mock_override.tf`, plans, emits `show -json`, and removes the override on a `trap` —
including on failure. The override is deliberately **not** stored inside either root: a
stray copy there would silently point a real `apply` at a mock endpoint, and resources
reported as created that do not exist is a worse failure than any error.

What this gives is genuine: 95 resources, real module wiring, real `for_each` expansion,
real rendered policy documents. What it does not give is a diff against real state, so it
always reports create-everything. That is a check on the **shape** of the configuration,
which is what a reviewer looking at a diff needs. Invariants are `tf-test.sh`'s job and
policy is Checkov's.

---

## The triage agent

`tools/aiops/triage.py` takes a firing Alertmanager payload, gathers evidence from
Prometheus and Loki, and drafts a probable cause.

It is **read-only by construction, and the self-test enforces it** by grepping the source
for `subprocess`, `kubectl`, `boto3`, `os.system` and any POST. The contract is not
"we intend not to mutate"; it is a test that fails if a mutating import appears.

One detail carries the whole Phase 11 lesson forward: `promql()` returns the *string*
`no data` for an empty vector, never `0`. Gap row 32 exists because an empty Prometheus
result scored as success. An agent that renders "error rate: 0%" when the query returned
nothing would be the same defect with better grammar.

It prints every query it ran, so its reasoning can be checked rather than believed.

---

## Testing a tool whose output is prose

`scripts/aiops-selftest.sh` — 62 assertions, offline: no cluster, no AWS, no model, no
network.

It is **two-sided**. A dangerous fixture must raise each finding, *and* a safe fixture must
stay quiet. One-sided tests are how a checker that flags everything passes its own suite.

It also asserts things that are not about detection at all: that Python's `True` never
leaks into HCL-flavoured output, that Checkov severities are preserved rather than
flattened, that the header leads with the worst severity, and that `AGENTS.md` still
contains its three specific clauses.

### The self-test was itself tested

62 green assertions prove nothing until you know they can go red. Five mutations were
introduced into `risk.py` one at a time:

| Mutation | Caught? |
|---|---|
| replacement detection made order-sensitive | ✅ |
| `force_destroy` removed from `GUARDRAILS` | ✅ |
| cost threshold raised past the fixture | ✅ |
| `critical` downgraded to `high` | ✅ |
| **`mode != "managed"` filter removed** | ❌ **no** |

The fifth was a genuine hole. It was closed by adding a `data "aws_security_group"` with
`0.0.0.0/0` ingress to the safe fixture plus two `assert_lacks` — and that mutation now
produces four failures.

Finding a gap in the tests is the point of the exercise. A mutation run where everything is
caught on the first try mostly means the mutations were too obvious.

---

## In CI

The `aiops` job does two things, and only the first one gates:

- **`aiops-selftest.sh` blocks.** Offline, fast, cannot flake.
- **The comment is advisory and cannot fail the build.** Every step that produces it is
  `continue-on-error: true`. A second opinion that can block a merge has stopped being
  advice — and a model-assisted comment that gates would put the fail-open thing back on
  the critical path. `tf-validate.sh`, `tf-test.sh` and Checkov are the gates.

The comment carries an HTML marker and is **updated in place**, not appended. A new comment
per push turns a busy PR into a wall of near-identical tables, which is the 25-IAM-findings
mistake again at a different scale.

---

## Tier P

Bedrock is not available in the KodeKloud playground, which is why the provider layer
exists at all. In Tier P it is a config swap: `AIOPS_PROVIDER=bedrock`, a read-only IRSA
role, no code change. `boto3` is imported lazily inside the Bedrock branch so the other
providers do not pay for a dependency they never use.

The commenter itself has **no AWS dependency in any tier** — which is why it is the piece
of this phase that actually works everywhere.

---

## Related

- `AGENTS.md` — the safe-execution contract these agents operate under
- `docs/06-load-and-delivery.md` — the fail-open defect class this design reacts to
- `docs/16-gap-register.md` — row 20 (Bedrock), rows 32–39 (fail-open gates)
