variable "name" {
  description = "Name prefix. The bucket gets a random suffix on top, because bucket names are globally unique."
  type        = string
}

variable "force_destroy" {
  description = "Allow terraform destroy to empty the bucket first. True in Tier S, where teardown must always succeed."
  type        = bool
  default     = false
}

variable "versioning" {
  description = "Keep previous object versions. Off in Tier S; the account does not outlive the session."
  type        = bool
  default     = true
}

variable "log_retention_days" {
  description = "Days to keep S3 access logs before expiry. Access logs grow without bound and are the classic source of a surprise storage bill."
  type        = number
  default     = 90
}

variable "kms_key_arn" {
  description = "Customer-managed key. Null falls back to SSE-S3, which is free and still encrypts at rest."
  type        = string
  default     = null
}

variable "cors_origins" {
  description = "Origins permitted to upload directly with a presigned URL."
  type        = list(string)
  default     = []

  validation {
    condition     = !contains(var.cors_origins, "*")
    error_message = "A wildcard CORS origin lets any site on the internet drive an authenticated browser's presigned upload; name the origins."
  }
}

variable "tags" {
  description = "Tags applied to the bucket."
  type        = map(string)
  default     = {}
}
