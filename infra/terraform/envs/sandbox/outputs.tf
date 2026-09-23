output "region" {
  value = var.region
}

output "table_names" {
  description = "Prefixed table names, for DYNAMODB_TABLE_PREFIX in the Helm values."
  value       = module.dynamodb.table_names
}

output "media_bucket" {
  value = module.storage.bucket_name
}

output "ecr_repositories" {
  description = "Feeds image.repository per service."
  value       = module.registry.repository_urls
}

output "kubeconfig_command" {
  description = "Run this, then kubectl works."
  value       = var.enable_eks ? "aws eks update-kubeconfig --region ${var.region} --name ${module.eks[0].cluster_name}" : "EKS not enabled; set enable_eks = true"
}

output "search_db_jdbc_url" {
  value = var.enable_search_db ? module.search_db[0].jdbc_url : null
}

output "search_db_password" {
  value     = var.enable_search_db ? module.search_db[0].master_password : null
  sensitive = true
}

output "irsa_role_arns" {
  description = "Empty in the usual case. That emptiness is the gap."
  value       = var.enable_irsa && var.enable_eks ? module.irsa[0].role_arns : {}
}

output "nodes_are_public" {
  description = "True here. Surfaced as an output so it appears in every apply rather than only in a document nobody re-reads."
  value       = module.network.nodes_are_public
}
