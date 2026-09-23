# Infrastructure

Terraform for AWS. Seven modules, two roots, and a deliberate refusal to pretend the
two roots are the same thing.

```
infra/terraform/
├── modules/
│   ├── network/     VPC, subnets, NAT, gateway endpoints
│   ├── dynamodb/    the nine tables, read from tools/dynamodb-tables.json
│   ├── storage/     S3 media bucket
│   ├── registry/    one ECR repository per service
│   ├── database/    RDS Postgres — the search index, and nothing else
│   ├── eks/         cluster, node group, OIDC provider
│   └── iam/         per-service IRSA roles
└── envs/
    ├── sandbox/     Tier S — applied against the KodeKloud playground
    └── prod/        Tier P — never applied; exists to be diffed
```

## 1. Why two roots and not one with a workspace

A workspace gives you one configuration with different variables. That is the right
shape when environments differ by size. These environments differ by *structure*:
Tier S adopts a pre-existing IAM role because it cannot create one, skips the OIDC
provider entirely, and runs its nodes in a default VPC's public subnets. Expressing
that as `count = var.is_prod ? 1 : 0` scattered through a single root produces a file
where neither environment is legible.

Two roots means the diff between them is a single `diff` command, and that diff is
the deliverable. Read `envs/sandbox/main.tf` and `envs/prod/main.tf` side by side;
every difference is a control the playground cannot express.

The cost is real: a change to one root can be forgotten in the other. That is what
the assertions in `scripts/tf-validate.sh` are for, and it is why the per-service
IRSA grants live as a **default** on `modules/iam`'s `services` variable rather than
being restated in each root — the one thing that genuinely must not drift is defined
once.

## 2. The table schema has exactly one home

`tools/dynamodb-tables.json` defines all nine tables. Three things read it:

- `tools/localstack/create-tables.py`, for Tier L
- `modules/dynamodb`, via `jsondecode(file(...))`, for Tiers S and P
- `scripts/tf-validate.sh`, which asserts the other two still do

Restating the schema in HCL is the obvious thing to do and it is wrong. LocalStack and
AWS would drift, and the first symptom is a `ValidationException` on a query against a
GSI that exists locally — an error that points at the query, not at the schema.

The module also carries a `lifecycle.precondition` that fails at plan time if an
attribute is declared but never used by a key or index. DynamoDB rejects that at apply
time with a message that names the table rather than the attribute.

## 3. What each tier actually is

| | Tier S (sandbox) | Tier P (prod) |
|---|---|---|
| VPC | the account's default | purpose-built, 3 AZ, NAT per AZ |
| Nodes | public subnets | private subnets |
| API endpoint | public, `0.0.0.0/0` | private, named CIDRs, validated non-open |
| Cluster IAM role | adopted (`eksClusterRole`) | created |
| OIDC provider | usually impossible | created |
| Pod credentials | **the node instance role** | per-service IRSA |
| Encryption | AWS-managed keys | customer-managed KMS, incl. etcd envelope |
| DynamoDB PITR | off | on |
| Deletion protection | off everywhere | on everywhere |
| RDS | `db.t3.micro`, single-AZ, no backups | `db.m6g.large`, Multi-AZ, 7-day backups |
| State | local, gitignored | S3, encrypted, `use_lockfile` |

The single most consequential row is pod credentials. Without an OIDC provider there
is no IRSA, so every pod falls back to the node instance role and gets every
permission the node has. The per-service isolation this design is partly about does
not exist in Tier S — which is exactly why the chart's NetworkPolicy blocks
`169.254.169.254/32`, and why that block is the most load-bearing line in it.

## 4. Why RDS at all, when everything else is DynamoDB

The search index, and only the search index. Full-text search is the one access
pattern DynamoDB genuinely cannot serve, and the answer is a purpose-built index
alongside it rather than bending the primary store into a shape it is bad at.

Every row in that database is derived from the `tweets` table by the indexer. Losing
the whole instance costs a reindex, not a restore. That single fact is what makes a
single-AZ `db.t3.micro` with no backups defensible in Tier S.

Tier P still runs it Multi-AZ — not for durability, but for failover. A single-AZ
replacement is a ten-minute reindex during which search returns nothing, and "search
is down" reads to a user as "the site is broken".

## 5. Staging the sandbox apply

`enable_eks`, `enable_search_db` and `enable_irsa` all default to `false`.

The session is 180 minutes. An EKS control plane takes 10–12 minutes and its node
group another 5; RDS adds 8. Applying everything at once spends a fifth of the session
before anything is deployable, and if the apply fails at minute 14 you have learned
one thing very slowly.

Bring the data plane up first — DynamoDB, S3, ECR, about three minutes — verify it,
then flip `enable_eks`.

Both role ARNs in `terraform.tfvars` change every time the playground is recycled.
`terraform.tfvars.example` has the two `aws iam` commands that find them.

## 6. State

Tier S keeps state in a local, gitignored file. The remote-state answer is an S3
bucket with locking, and it is right everywhere except here: the bucket would live in
the same 180-minute account as the infrastructure it describes, so it dies with its
own backend.

Tier P declares the S3 backend properly — commented out, because the bucket does not
exist and a real `init` would fail and take the CI job with it. The declaration stays
so the difference is visible in the file rather than only in prose.

Note `use_lockfile` rather than a DynamoDB lock table. S3 conditional writes do the
same job with one fewer resource to provision and one fewer to forget.

State contains the generated RDS master password in plaintext. `scripts/tf-validate.sh`
fails if any `.tfstate` or `.tfvars` file is tracked by git.

## 7. Validation

```
make tf-validate
```

Four layers, in increasing order of usefulness:

1. `terraform fmt -check`
2. `terraform validate` on all seven modules and both roots
3. `tflint` and `checkov`
4. assertions specific to this design

Layer 4 exists because `terraform validate` type-checks a configuration; it has no
opinion about whether the configuration is correct. A production root with deletion
protection off and a public bucket validates cleanly.

So the script asserts, among others: both roots read the one schema file; Tier P keeps
PITR, deletion protection, bucket versioning, customer-managed KMS, etcd envelope
encryption, a private API endpoint and a purpose-built VPC; Tier P never sets
`force_destroy` or `force_delete`; Tier S stays destroyable; no real AWS account id
appears in any `.tf` file; every IRSA trust policy pins both `:sub` and `:aud`; and
the service names in `modules/iam` match the workload names in `deploy/envs/prod/`.

Checkov runs hard against Tier P and advisory-only against Tier S. Tier S is
*deliberately* non-compliant, and a scanner that reports the intended gaps as failures
teaches people to add `--skip-check` until it is silent.

All of these were verified by deliberately breaking them and confirming the suite goes
red. An assertion nobody has seen fail is an assertion nobody should trust.

### 7.1 The test layer

```
make tf-test
```

Layers 1–4 all *read* the configuration. None of them evaluates it, and that turns out
to be a real hole rather than a theoretical one.

`scripts/tf-test.sh` runs `terraform test` under `mock_provider` across all seven modules
and both roots — 47 run blocks, no credentials, no AWS calls. Because it evaluates the
config, it sees what reading it cannot: `jsondecode` of the shared table-schema file,
`for_each` expansion, `variable` validation blocks, lifecycle preconditions, and how the
modules compose when a root wires them together.

Writing it found one real bug. `modules/database` used `for_each = toset(var.allowed_security_group_ids)`
and the production root passed `module.eks.cluster_security_group_id`, which does not exist
until apply. Terraform needs `for_each` keys known at plan time, so the **first** apply of a
clean root would have failed — and only the first, since afterwards the id is in state. In a
180-minute disposable session the first apply is the only apply. See gap-register row 20.

The three layers are not redundant, and the division is structural rather than stylistic:

| layer | sees | cannot see |
| --- | --- | --- |
| `terraform validate` | types, references, syntax | whether any of it is a good idea |
| `tf-validate.sh` (grep) | the **absence** of a resource, attribute or argument | anything computed |
| `tf-test.sh` (evaluate) | computed values, expansion, composition | absence — naming an undeclared resource is a parse error, not a failed assertion |

That last cell is why "the search database has no egress rule" and "database ingress is
never by CIDR" live in `tf-validate.sh` and not in a `.tftest.hcl`.

Two limits of `mock_provider` are worth knowing before reading the suites. It returns
random strings for provider-computed attributes, so `aws_iam_policy_document.json` has to
be mocked with real JSON or `aws_iam_policy` rejects it — which means policy *content* is
not assertable under mocks and those assertions stay in `tf-validate.sh`. And at root scope
a test sees module **outputs** only, never the resources inside the modules, so the prod
root asserts on what the modules choose to expose (`module.network.nodes_are_public == false`
being the sharpest one).

## 8. What is not here

No Terraform for the Kubernetes layer. The cluster is created by Terraform and
everything inside it is Helm and ArgoCD. Using the Kubernetes provider from Terraform
means the cluster must exist before the plan can be computed, which makes the first
apply of a fresh environment a two-stage manual process and every subsequent plan
dependent on cluster reachability.

No Terraform Cloud, no Atlantis, no `terraform apply` in CI. The CI job configures no
AWS credentials at all, so it cannot be turned into one that applies by a one-line
pull request.
