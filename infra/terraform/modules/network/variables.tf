variable "name" {
  description = "Name prefix for every resource in this module."
  type        = string
}

variable "cluster_name" {
  description = "EKS cluster name, needed for the subnet tags the load balancer controller requires."
  type        = string
}

variable "region" {
  description = "AWS region, used to build VPC endpoint service names."
  type        = string
}

variable "use_default_vpc" {
  description = <<-EOT
    Adopt the account's default VPC instead of creating one.

    True in Tier S: the playground provides a default VPC, forbids NAT gateways in
    practice, and wipes the account between sessions, so creating a VPC costs
    minutes of a 180-minute budget and buys nothing. False everywhere else.
  EOT
  type        = bool
  default     = false
}

variable "cidr" {
  description = "VPC CIDR. Ignored when use_default_vpc is true."
  type        = string
  default     = "10.0.0.0/16"

  validation {
    # /16 to /20. Larger wastes nothing but signals confusion; smaller cannot be
    # divided into the six subnets cidrsubnet() carves out below, and the error
    # Terraform gives for that is about a bit count, not about the CIDR.
    condition     = can(cidrnetmask(var.cidr)) && tonumber(split("/", var.cidr)[1]) <= 20
    error_message = "cidr must be a valid CIDR of /20 or larger (e.g. 10.0.0.0/16)."
  }
}

variable "azs" {
  description = "Availability zones. Ignored when use_default_vpc is true."
  type        = list(string)
  default     = []

  validation {
    condition     = length(var.azs) == 0 || length(var.azs) >= 2
    error_message = "A single availability zone cannot survive an AZ failure; use at least two."
  }
}

variable "tags" {
  description = "Tags applied to every resource this module creates."
  type        = map(string)
  default     = {}
}

variable "flow_log_retention_days" {
  description = "Days to keep VPC flow logs. A year is the audit-useful floor -- flow logs answer questions asked long after the incident -- and the default of never-expire is the single most common source of an unexplained CloudWatch bill."
  type        = number
  default     = 365
}

variable "log_kms_key_arn" {
  description = "Customer-managed key for the flow log group. Null uses the CloudWatch service key, which still encrypts at rest."
  type        = string
  default     = null
}
