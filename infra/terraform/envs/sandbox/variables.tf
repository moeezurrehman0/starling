variable "region" {
  description = "Playground region. KodeKloud pins us-east-1 for most services."
  type        = string
  default     = "us-east-1"
}

variable "name" {
  description = "Name prefix for every resource in this root."
  type        = string
  default     = "starling-sbx"
}

variable "namespace" {
  description = "Kubernetes namespace the workloads run in."
  type        = string
  default     = "starling"
}

variable "cluster_role_arn" {
  description = <<-EOT
    The playground's pre-created EKS cluster role.

    The account forbids iam:CreateRole, so the cluster adopts what exists. Look it
    up with `aws iam list-roles --query "Roles[?contains(RoleName,'eks')]"` at the
    start of each session -- the account id changes every time, which is itself
    the reason nothing here can be committed with a real ARN.
  EOT
  type        = string
}

variable "node_role_arn" {
  description = "The playground's pre-created node instance role, found the same way."
  type        = string
}

variable "enable_eks" {
  description = <<-EOT
    Build the cluster.

    Default false. An EKS control plane takes 10-12 minutes and a node group
    another 5, which is a fifth of the session spent before anything is
    deployable. Bring the data plane up first, verify it, then flip this.
  EOT
  type        = bool
  default     = false
}

variable "enable_search_db" {
  description = "Build the RDS search index. Default false: another 8 minutes, and search is not on the critical path for most probes."
  type        = bool
  default     = false
}

variable "enable_irsa" {
  description = <<-EOT
    Create per-service IRSA roles.

    Requires iam:CreateRole and iam:CreateOpenIDConnectProvider, both of which the
    playground may deny. Default is true because the alternative is not "less
    isolation", it is no AWS access at all: this used to default to false and say
    that pods would fall back to the node instance role, which was wrong. The node
    role carries AmazonEKSWorkerNodePolicy, AmazonEKS_CNI_Policy and
    AmazonEC2ContainerRegistryReadOnly and nothing application-shaped, so the
    "fallback" granted no DynamoDB and no S3 -- and the chart's NetworkPolicy
    blocks 169.254.169.254 anyway, so it was unreachable as well as empty.

    That went unnoticed because Tier S ran the Tier L overlay and talked to an
    in-cluster LocalStack, which needs no credentials. See ADR-0014 and gap
    register row 9.

    If the apply fails on iam:CreateRole or iam:CreateOpenIDConnectProvider, set
    this false AND enable_node_role_fallback true. That is a real degradation and
    is meant to look like one.
  EOT
  type        = bool
  default     = true
}

variable "enable_node_role_fallback" {
  description = <<-EOT
    Grant the application permissions to the node instance role instead of to
    per-service roles, for the case where the playground denies IRSA.

    Every pod on the node then holds every permission, including pods that should
    only read. This is the isolation gap in register row 9 made real rather than
    merely claimed, and it additionally requires networkPolicy.allowImds on the
    service chart, because credentials arrive over the link-local address the
    chart blocks by default.

    Default false. Turning it on is a deliberate, recorded downgrade, not a
    convenience -- and it must never be true at the same time as enable_irsa.
  EOT
  type        = bool
  default     = false
}

variable "services" {
  description = "Services that get an ECR repository."
  type        = list(string)
  default     = ["gateway", "user-service", "tweet-service", "timeline-service", "fanout-worker", "web"]
}
