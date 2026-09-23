variable "name" {
  description = "Repository namespace, e.g. twitter-clone."
  type        = string
}

variable "services" {
  description = "One repository is created per entry."
  type        = list(string)
}

variable "keep_last" {
  description = <<-EOT
    How many sha-tagged images to retain per repository.

    Five is enough to roll back through a bad week and small enough that storage
    stays negligible. It is also the reason a rollback target must be pinned, not
    assumed: an image older than the window is gone, and the failure appears as an
    ImagePullBackOff during an incident.
  EOT
  type        = number
  default     = 5

  validation {
    condition     = var.keep_last >= 2
    error_message = "Keeping fewer than two images makes rollback impossible by construction."
  }
}

variable "kms_key_arn" {
  description = "Customer-managed key for image layers. Null uses AES256, which is free."
  type        = string
  default     = null
}

variable "force_delete" {
  description = "Allow destroy to remove repositories that still contain images. True in Tier S."
  type        = bool
  default     = false
}

variable "tags" {
  description = "Tags applied to every repository."
  type        = map(string)
  default     = {}
}
