variable "region" {
  description = "Playground region. KodeKloud pins us-east-1 for most services."
  type        = string
  default     = "us-east-1"
}

variable "name" {
  description = "Name prefix for every resource in this root."
  type        = string
  default     = "twitter-clone-sbx"
}

variable "namespace" {
  description = "Kubernetes namespace the workloads run in."
  type        = string
  default     = "twitter-clone"
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
    playground usually denies. When false, pods fall back to the node instance
    role -- every pod gets every permission, and the per-service isolation this
    project is partly about simply does not exist in Tier S. Gap-register row 9.
  EOT
  type        = bool
  default     = false
}

variable "services" {
  description = "Services that get an ECR repository."
  type        = list(string)
  default     = ["gateway", "user-service", "tweet-service", "timeline-service", "fanout-worker", "web"]
}
