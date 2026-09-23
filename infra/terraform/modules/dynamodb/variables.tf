variable "definitions_file" {
  description = "Path to tools/dynamodb-tables.json — the single source of truth shared with the Compose bootstrap."
  type        = string
}

variable "table_prefix" {
  description = "Prefix for every table name, so two environments can share an account."
  type        = string
  default     = ""
}

variable "point_in_time_recovery" {
  description = "Continuous backups with 35-day restore. Off in Tier S; the account does not survive 180 minutes."
  type        = bool
  default     = false
}

variable "kms_key_arn" {
  description = "Customer-managed KMS key. Null uses the AWS-owned key, which is free and still encrypts at rest."
  type        = string
  default     = null
}

variable "deletion_protection" {
  description = "Refuse to delete these tables. Must be false in Tier S, where teardown is the point."
  type        = bool
  default     = false
}

variable "tags" {
  description = "Tags applied to every table."
  type        = map(string)
  default     = {}
}
