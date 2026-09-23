output "endpoint" {
  description = "host:port."
  value       = aws_db_instance.this.endpoint
}

output "jdbc_url" {
  description = "Ready to drop into SEARCH_DB_URL."
  value       = "jdbc:postgresql://${aws_db_instance.this.endpoint}/${var.database_name}"
}

output "security_group_id" {
  description = "The database's security group, so callers can be granted access by reference."
  value       = aws_security_group.this.id
}

output "master_password" {
  description = "Generated master password. Marked sensitive so it does not land in a CI log; it is still in state, which is why Tier P keeps state encrypted and Tier S keeps it local and gitignored."
  value       = random_password.master.result
  sensitive   = true
}

output "secret_arn" {
  description = "Secrets Manager ARN, or null when use_secrets_manager is false."
  value       = var.use_secrets_manager ? aws_secretsmanager_secret.master[0].arn : null
}
