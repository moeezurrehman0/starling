# Unit tests for the network module.

mock_provider "aws" {
  # The flow-log role's assume_role_policy is a policy document's rendered JSON,
  # and the provider validates it as JSON before the plan is produced. A mocked
  # data source returns an arbitrary string, so without this the whole suite
  # fails on "not a JSON object" -- a mocking artefact that says nothing about
  # the module. Same fixture as the eks and iam suites.
  mock_data "aws_iam_policy_document" {
    defaults = {
      json = "{\"Version\":\"2012-10-17\",\"Statement\":[]}"
    }
  }
}

variables {
  name         = "starling"
  cluster_name = "starling"
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

# The security posture of a purpose-built VPC.
#
# scripts/tf-validate.sh greps for these resources because checkov cannot see
# them through a count-indexed reference. A grep proves the text is present; it
# does not prove the resources plan, or that they are wired to anything. These
# assertions are the other half.
run "a_purpose_built_vpc_logs_its_traffic_and_empties_the_default_security_group" {
  command = plan

  assert {
    condition     = length(aws_flow_log.this) == 1
    error_message = "no flow log; a connection timeout stays ambiguous between a REJECT and a packet that never arrived"
  }

  # ACCEPT-only is the tempting default because it is cheaper. It also discards
  # the only records that explain a failure, which is when anyone reads these.
  assert {
    condition     = aws_flow_log.this[0].traffic_type == "ALL"
    error_message = "flow logs must capture rejects; accept-only logs are silent in exactly the case they are needed"
  }

  # The default security group cannot be deleted. It can only be adopted and
  # emptied, and anything launched without an explicit group lands in it.
  assert {
    condition     = length(aws_default_security_group.this) == 1
    error_message = "the default security group was left as AWS ships it: allow-all from itself"
  }

  # A public IP assigned by default turns one mistaken subnet choice into an
  # internet-facing workload, with nothing in the diff to show for it.
  assert {
    condition     = alltrue([for s in aws_subnet.private : s.map_public_ip_on_launch == false])
    error_message = "private subnets must not auto-assign public IPs"
  }
}
