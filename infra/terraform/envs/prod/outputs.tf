output "irsa_role_arns" {
  description = "The ARNs deploy/envs/prod/*.yaml annotates. The committed values carry a placeholder account id on purpose."
  value       = module.irsa.role_arns
}

output "irsa_helm_values" {
  description = "The same, as a pasteable YAML fragment."
  value       = module.irsa.helm_values
}

output "ecr_repositories" {
  value = module.registry.repository_urls
}

output "table_names" {
  value = module.dynamodb.table_names
}

output "media_bucket" {
  value = module.storage.bucket_name
}

output "cluster_name" {
  value = module.eks.cluster_name
}

output "nodes_are_public" {
  description = "False. The single most important difference from Tier S."
  value       = module.network.nodes_are_public
}
