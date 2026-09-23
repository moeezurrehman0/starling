output "repository_urls" {
  description = "Map of service name to repository URL, for the image.repository values in the Helm charts."
  value       = { for k, r in aws_ecr_repository.this : k => r.repository_url }
}

output "repository_arns" {
  description = "Map of service name to ARN, for the node group's pull policy."
  value       = { for k, r in aws_ecr_repository.this : k => r.arn }
}
