output "role_arns" {
  description = "Service name to role ARN. Feeds serviceAccount.roleArn in deploy/envs/<env>/<service>.yaml."
  value       = { for k, r in aws_iam_role.this : k => r.arn }
}

output "helm_values" {
  description = <<-EOT
    The same map rendered as the YAML fragment each service's values file needs,
    so the ARNs are copied rather than retyped. The placeholder account id in the
    committed prod values is deliberate -- prod is never applied, and a real
    account id in git is a finding.
  EOT
  value       = join("\n", [for k, r in aws_iam_role.this : "${k}:\n  serviceAccount:\n    roleArn: ${r.arn}"])
}
