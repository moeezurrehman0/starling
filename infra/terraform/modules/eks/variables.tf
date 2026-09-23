variable "name" {
  description = "Cluster name; also the prefix for the roles this module may create."
  type        = string
}

variable "kubernetes_version" {
  description = "Control plane and node group version. Pinned: EKS force-upgrades a version past end of support, at a time it chooses."
  type        = string
  default     = "1.31"
}

variable "subnet_ids" {
  description = "Subnets for the control plane ENIs. At least two AZs."
  type        = list(string)

  validation {
    condition     = length(var.subnet_ids) >= 2
    error_message = "EKS requires subnets in at least two availability zones."
  }
}

variable "node_subnet_ids" {
  description = "Subnets for the node group. Private in Tier P; the default VPC's public subnets in Tier S."
  type        = list(string)
}

variable "cluster_security_group_ids" {
  description = "Extra security groups for the control plane ENIs. EKS always creates its own as well."
  type        = list(string)
  default     = []
}

variable "endpoint_public_access" {
  description = "Expose the Kubernetes API to the internet. True in Tier S, where there is no bastion."
  type        = bool
  default     = true
}

variable "public_access_cidrs" {
  description = "CIDRs allowed to reach the public endpoint. 0.0.0.0/0 is the AWS default and is a gap-register row, not a recommendation."
  type        = list(string)
  default     = ["0.0.0.0/0"]
}

variable "enabled_log_types" {
  description = "Control-plane log types. 'audit' answers who did what; the rest are cheap."
  type        = list(string)
  default     = ["api", "audit", "authenticator", "controllerManager", "scheduler"]
}

variable "create_log_group" {
  description = "Create the log group so retention can be bounded. False in Tier S, where logs:CreateLogGroup may be denied."
  type        = bool
  default     = true
}

variable "log_retention_days" {
  description = "Retention for the control-plane log group."
  type        = number
  default     = 30
}

variable "create_cluster_role" {
  description = "Create the cluster IAM role. False in Tier S, which adopts the playground's eksClusterRole."
  type        = bool
  default     = true
}

variable "existing_cluster_role_arn" {
  description = "Required when create_cluster_role is false."
  type        = string
  default     = null
}

variable "create_node_role" {
  description = "Create the node IAM role. False in Tier S."
  type        = bool
  default     = true
}

variable "existing_node_role_arn" {
  description = "Required when create_node_role is false."
  type        = string
  default     = null
}

variable "create_oidc_provider" {
  description = <<-EOT
    Register the cluster's OIDC issuer as an IAM identity provider.

    Without it there is no IRSA, and the only way for a pod to reach DynamoDB is
    the node instance role -- which grants it to every pod on the node. Tier S may
    be unable to create it (iam:CreateOpenIDConnectProvider is frequently denied),
    and that single missing resource is what forces the sandbox onto node-role
    credentials. It is the sharpest gap in the register.
  EOT
  type        = bool
  default     = true
}

variable "secrets_kms_key_arn" {
  description = "KMS key for envelope-encrypting Secrets in etcd. Null disables it, which leaves Secrets as base64."
  type        = string
  default     = null
}

variable "instance_types" {
  description = "Node instance types. t3.medium is the playground ceiling."
  type        = list(string)
  default     = ["t3.medium"]
}

variable "capacity_type" {
  description = "ON_DEMAND or SPOT."
  type        = string
  default     = "ON_DEMAND"

  validation {
    condition     = contains(["ON_DEMAND", "SPOT"], var.capacity_type)
    error_message = "capacity_type must be ON_DEMAND or SPOT."
  }
}

variable "ami_type" {
  description = "Managed node AMI family."
  type        = string
  default     = "AL2023_x86_64_STANDARD"
}

variable "disk_size" {
  description = "Root volume GB per node. 20 fills fast once images and ephemeral logs accumulate."
  type        = number
  default     = 30
}

variable "desired_size" {
  description = "Initial node count. Ignored after creation; the autoscaler owns it."
  type        = number
  default     = 3
}

variable "min_size" {
  description = "Floor. Two is the minimum that survives a single node's rolling replacement."
  type        = number
  default     = 2
}

variable "max_size" {
  description = "Ceiling. Three in Tier S, which is the playground's instance quota."
  type        = number
  default     = 3
}

variable "node_labels" {
  description = "Kubernetes labels applied to every node."
  type        = map(string)
  default     = {}
}

variable "addons" {
  description = <<-EOT
    EKS managed addons, keyed by addon name.

    Leave version empty to take the default for the cluster version. ebs-csi-driver
    needs an IRSA role in service_account_role_arn or its controller cannot create
    volumes -- and the failure surfaces as a PVC stuck Pending with no event that
    names IAM.
  EOT
  type = map(object({
    version                  = optional(string, "")
    service_account_role_arn = optional(string)
  }))
  default = {
    vpc-cni    = {}
    coredns    = {}
    kube-proxy = {}
  }
}

variable "tags" {
  description = "Tags applied to every resource."
  type        = map(string)
  default     = {}
}
