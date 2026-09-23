output "cluster_name" {
  description = "For `aws eks update-kubeconfig --name`."
  value       = aws_eks_cluster.this.name
}

output "cluster_endpoint" {
  description = "Kubernetes API endpoint."
  value       = aws_eks_cluster.this.endpoint
}

output "cluster_certificate_authority" {
  description = "Base64 CA bundle for the kubeconfig."
  value       = aws_eks_cluster.this.certificate_authority[0].data
}

output "cluster_security_group_id" {
  description = "The security group EKS creates for the control plane. Nodes and the database are granted access by reference to it."
  value       = aws_eks_cluster.this.vpc_config[0].cluster_security_group_id
}

output "oidc_issuer_url" {
  description = "The cluster's OIDC issuer."
  value       = aws_eks_cluster.this.identity[0].oidc[0].issuer
}

output "oidc_provider_arn" {
  description = "IAM identity provider ARN, or null when create_oidc_provider is false -- in which case there is no IRSA and the iam module must be disabled too."
  value       = var.create_oidc_provider ? aws_iam_openid_connect_provider.this[0].arn : null
}

output "node_role_arn" {
  description = "The node group's role, so callers can see exactly which permissions are shared by every pod."
  value       = local.node_role_arn
}

output "node_group_name" {
  description = "Managed node group name."
  value       = aws_eks_node_group.this.node_group_name
}
