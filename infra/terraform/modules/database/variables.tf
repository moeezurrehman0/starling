variable "name" {
  description = "Name prefix."
  type        = string
}

variable "vpc_id" {
  description = "VPC for the security group."
  type        = string
}

variable "subnet_ids" {
  description = "Subnets for the DB subnet group. Private in Tier P; the default VPC's public subnets in Tier S, which is gap-register row 2."
  type        = list(string)
}

variable "allowed_security_group_ids" {
  description = "Security groups permitted to reach port 5432. Referenced by id, never by CIDR."
  type        = list(string)
  default     = []
}

variable "engine_version" {
  description = "Postgres major.minor. Pinned, because an unpinned engine version moves under you at the next maintenance window."
  type        = string
  default     = "16.4"
}

variable "instance_class" {
  description = "Instance size. db.t3.micro in Tier S, which is free tier and the largest the playground reliably allows."
  type        = string
  default     = "db.t3.micro"
}

variable "allocated_storage" {
  description = "GB. 20 is the free-tier ceiling."
  type        = number
  default     = 20
}

variable "storage_type" {
  description = "gp3 where allowed; gp2 in Tier S for free-tier eligibility."
  type        = string
  default     = "gp3"
}

variable "database_name" {
  description = "Initial database name."
  type        = string
  default     = "twitter_search"
}

variable "master_username" {
  description = "Master user. Not 'postgres' and not 'admin', both of which are the first two guesses."
  type        = string
  default     = "twitter"
}

variable "multi_az" {
  description = "Standby in a second AZ. False in Tier S: this data is derived and a reindex is cheaper than the instance."
  type        = bool
  default     = false
}

variable "backup_retention_days" {
  description = "Automated backup window. Zero disables backups entirely, which is only defensible for derived data in a disposable account."
  type        = number
  default     = 7
}

variable "skip_final_snapshot" {
  description = "Skip the snapshot on destroy. True in Tier S, where the snapshot would outlive the account it belongs to."
  type        = bool
  default     = false
}

variable "deletion_protection" {
  description = "Refuse to delete. Must be false in Tier S."
  type        = bool
  default     = true
}

variable "apply_immediately" {
  description = "Apply changes outside the maintenance window. True in Tier S, where there is no next window."
  type        = bool
  default     = false
}

variable "log_exports" {
  description = "Log types shipped to CloudWatch."
  type        = list(string)
  default     = ["postgresql", "upgrade"]
}

variable "performance_insights" {
  description = "Performance Insights. Not available on db.t3.micro, so false in Tier S."
  type        = bool
  default     = true
}

variable "use_secrets_manager" {
  description = "Store the generated master password in Secrets Manager. False in Tier S; see secret_recovery_days."
  type        = bool
  default     = true
}

variable "secret_recovery_days" {
  description = "Secrets Manager deletion window. The minimum is 7, which outlives a 180-minute sandbox session."
  type        = number
  default     = 7
}

variable "kms_key_arn" {
  description = "Customer-managed key for storage encryption. Null uses the AWS-managed RDS key."
  type        = string
  default     = null
}

variable "tags" {
  description = "Tags applied to every resource."
  type        = map(string)
  default     = {}
}
