output "vpc_id" {
  description = "The VPC the cluster runs in, whether adopted or created."
  value       = var.use_default_vpc ? data.aws_vpc.default[0].id : aws_vpc.this[0].id
}

output "public_subnet_ids" {
  description = "Subnets for public load balancers. In Tier S these are the default VPC's subnets, which are the only subnets there are."
  value       = var.use_default_vpc ? data.aws_subnets.default[0].ids : [for s in aws_subnet.public : s.id]
}

output "node_subnet_ids" {
  description = <<-EOT
    Subnets for the EKS node group.

    Public in Tier S and private in Tier P. This single difference is the largest
    security gap between the two tiers and is gap-register row 2 -- nodes with
    public addresses are reachable from the internet, and only a security group
    stands between them and the world.
  EOT
  value       = var.use_default_vpc ? data.aws_subnets.default[0].ids : [for s in aws_subnet.private : s.id]
}

output "nodes_are_public" {
  description = "True when the node group runs in public subnets. Consumed by the gap report so the deviation is asserted rather than remembered."
  value       = var.use_default_vpc
}
