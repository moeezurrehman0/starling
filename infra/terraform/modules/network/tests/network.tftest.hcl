# Unit tests for the network module.

mock_provider "aws" {}

variables {
  name         = "twitter-clone"
  cluster_name = "twitter-clone"
  region       = "eu-central-1"
  azs          = ["eu-central-1a", "eu-central-1b"]
}

run "a_purpose_built_vpc_has_private_subnets_and_a_way_out" {
  command = plan

  assert {
    condition     = length(aws_vpc.this) == 1
    error_message = "no VPC was created; the default is supposed to build one and only the sandbox opts out"
  }

  assert {
    condition     = length(aws_subnet.private) == 2
    error_message = "expected one private subnet per AZ"
  }

  # Nodes in private subnets still need to reach ECR and the EKS API. Without a
  # NAT the cluster creates, the nodes boot, and they never join -- which
  # presents as a node group stuck in CREATING with no useful error.
  assert {
    condition     = length(aws_nat_gateway.this) > 0
    error_message = "private subnets with no NAT; nodes will boot and never join, and the node group hangs in CREATING"
  }
}

run "the_s3_gateway_endpoint_exists_because_media_traffic_should_not_pay_nat" {
  command = plan

  # A gateway endpoint is free. Without it every media upload and download
  # crosses the NAT gateway at per-GB rates, which for a media-heavy workload is
  # the largest line on the bill and looks like nothing in particular.
  assert {
    condition     = length(aws_vpc_endpoint.gateway) > 0
    error_message = "no S3 gateway endpoint; all media traffic is billed through NAT for no benefit"
  }
}

run "the_sandbox_adopts_the_default_vpc_and_builds_nothing" {
  command = plan

  variables {
    use_default_vpc = true
  }

  # The playground's IAM is largely read-only and a VPC create is a likely
  # denial. Tier S tags the default VPC's subnets instead so the load balancer
  # controller can find them -- untagged subnets produce an ALB that never
  # provisions, with the reason only in the controller's logs.
  assert {
    condition     = length(aws_vpc.this) == 0
    error_message = "use_default_vpc = true still created a VPC"
  }

  assert {
    condition     = length(aws_nat_gateway.this) == 0
    error_message = "use_default_vpc = true still created a NAT gateway -- billed per hour inside a 180-minute session"
  }
}
