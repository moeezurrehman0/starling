variable "region" {
  description = "Primary region."
  type        = string
  default     = "eu-central-1"
}

variable "name" {
  description = "Name prefix."
  type        = string
  default     = "starling-prod"
}

variable "namespace" {
  description = "Kubernetes namespace the workloads run in."
  type        = string
  default     = "starling"
}

variable "vpc_cidr" {
  description = "VPC range. /16 leaves room for the EKS CNI, which allocates a real VPC address per pod and exhausts a small range faster than anyone expects."
  type        = string
  default     = "10.40.0.0/16"
}

variable "azs" {
  description = "Three AZs. Two survives one failure but leaves no headroom during a rolling replacement."
  type        = list(string)
  default     = ["eu-central-1a", "eu-central-1b", "eu-central-1c"]
}

variable "api_access_cidrs" {
  description = "CIDRs permitted to reach the Kubernetes API. Office and CI egress only; never 0.0.0.0/0."
  type        = list(string)
  default     = ["203.0.113.0/24"]

  validation {
    condition     = !contains(var.api_access_cidrs, "0.0.0.0/0")
    error_message = "The production API endpoint must not be open to the internet."
  }
}

variable "web_origins" {
  description = "Allowed CORS origins for the media bucket."
  type        = list(string)
  default     = ["https://app.example.com"]
}

variable "services" {
  description = "Services that get an ECR repository."
  type        = list(string)
  default     = ["gateway", "user-service", "tweet-service", "timeline-service", "fanout-worker", "web"]
}
