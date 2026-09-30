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

output "table_prefix" {
  description = <<-EOT
    The prefix every DynamoDB table name carries in this root.

    Exported because the application needs it and cannot derive it. Tier L runs
    unprefixed tables, so the dev overlay sets DYNAMODB_TABLE_PREFIX to the empty
    string; carrying that value into Tier S names tables that do not exist, and
    the SDK reports ResourceNotFoundException on the first write rather than at
    startup -- which is to say, during the demo rather than during provisioning.
  EOT
  value       = "${var.name}-"
}

output "pod_aws_identity" {
  description = <<-EOT
    How pods will obtain AWS credentials, as one word, so the bootstrap can check
    it and stop rather than discovering the answer at demo time.

    `irsa`          -- per-service roles, the intended state.
    `node-role`     -- the coarse fallback; every pod holds every permission.
    `none`          -- neither. Pods cannot reach DynamoDB or S3 at all. This was
                       the silent state before ADR-0014, survivable only because
                       Tier S was accidentally talking to LocalStack.
  EOT
  value = (
    var.enable_irsa && var.enable_eks ? "irsa" :
    var.enable_node_role_fallback && var.enable_eks ? "node-role" :
    "none"
  )
}
