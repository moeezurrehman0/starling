# Network.
#
# Two mutually exclusive shapes behind one flag, because the sandbox and
# production disagree about the most expensive object in the account.
#
#   use_default_vpc = true   (Tier S) -- adopt the playground's default VPC.
#   use_default_vpc = false  (Tier P) -- three-AZ VPC, private subnets, NAT.
#
# The alternative would be two separate modules, which is how the two shapes
# drift: a tag convention or a subnet-tag requirement gets fixed in one and not
# the other, and the sandbox stops predicting anything. One module with a flag
# means the EKS subnet tags below are provably identical in both.

data "aws_vpc" "default" {
  count   = var.use_default_vpc ? 1 : 0
  default = true
}

data "aws_subnets" "default" {
  count = var.use_default_vpc ? 1 : 0
  filter {
    name   = "vpc-id"
    values = [data.aws_vpc.default[0].id]
  }
}

# EKS will not place a load balancer in a subnet that is not tagged for it, and
# the failure is silent: the Service stays in <pending> with no event explaining
# why. The default VPC has no such tags, so we add them -- to a VPC we do not own
# and cannot replace, which is why this is a separate resource rather than a
# property of the subnet.
resource "aws_ec2_tag" "default_subnet_elb" {
  for_each = var.use_default_vpc ? toset(data.aws_subnets.default[0].ids) : toset([])

  resource_id = each.value
  key         = "kubernetes.io/role/elb"
  value       = "1"
}

resource "aws_ec2_tag" "default_subnet_cluster" {
  for_each = var.use_default_vpc ? toset(data.aws_subnets.default[0].ids) : toset([])

  resource_id = each.value
  key         = "kubernetes.io/cluster/${var.cluster_name}"
  value       = "shared"
}

# --- Tier P ------------------------------------------------------------------

resource "aws_vpc" "this" {
  # checkov:skip=CKV2_AWS_11:Flow logs ARE configured -- aws_flow_log.this below.
  #   Checkov's graph resolver does not follow a vpc_id that points at a
  #   count-indexed resource (aws_vpc.this[0]), so it cannot see the link. Proven
  #   instead by an assertion in scripts/tf-validate.sh, which fails if the
  #   aws_flow_log resource is ever removed.
  # checkov:skip=CKV2_AWS_12:Same resolver limitation. The default security group
  #   is adopted and emptied by aws_default_security_group.this below, and
  #   scripts/tf-validate.sh asserts it.
  count = var.use_default_vpc ? 0 : 1

  cidr_block = var.cidr
  # Both required by EKS. Without DNS hostnames the kubelet cannot resolve the
  # API endpoint through a VPC endpoint, and the nodes fail to join with a TLS
  # error that names neither DNS nor the endpoint.
  enable_dns_support   = true
  enable_dns_hostnames = true

  tags = merge(var.tags, { Name = var.name })
}

# Flow logs.
#
# The reason to want these is not the checkbox. When a pod cannot reach the
# database the symptom is a connection timeout, and a timeout is indistinguishable
# between a missing security-group rule, a missing route, and a NAT gateway that
# is gone. Flow logs are the only artefact that tells REJECT from "no packet ever
# arrived", which collapses that three-way ambiguity to one answer.
#
# Tier P only: the sandbox adopts the default VPC, which this module does not own
# and must not reconfigure.
resource "aws_cloudwatch_log_group" "flow" {
  count = var.use_default_vpc ? 0 : 1

  name              = "/aws/vpc/${var.name}/flow-logs"
  retention_in_days = var.flow_log_retention_days
  kms_key_id        = var.log_kms_key_arn

  tags = merge(var.tags, { Name = "${var.name}-flow-logs" })
}

data "aws_iam_policy_document" "flow_assume" {
  count = var.use_default_vpc ? 0 : 1

  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["vpc-flow-logs.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "flow" {
  count = var.use_default_vpc ? 0 : 1

  name               = "${var.name}-vpc-flow-logs"
  assume_role_policy = data.aws_iam_policy_document.flow_assume[0].json
  tags               = var.tags
}

data "aws_iam_policy_document" "flow" {
  count = var.use_default_vpc ? 0 : 1

  statement {
    actions = [
      "logs:CreateLogStream",
      "logs:PutLogEvents",
      "logs:DescribeLogStreams",
    ]
    # Scoped to this log group and its streams. The AWS-documented example for
    # this role uses "*", which hands every log group in the account to anything
    # that can assume it.
    resources = [
      aws_cloudwatch_log_group.flow[0].arn,
      "${aws_cloudwatch_log_group.flow[0].arn}:*",
    ]
  }
}

resource "aws_iam_role_policy" "flow" {
  count = var.use_default_vpc ? 0 : 1

  name   = "${var.name}-vpc-flow-logs"
  role   = aws_iam_role.flow[0].id
  policy = data.aws_iam_policy_document.flow[0].json
}

resource "aws_flow_log" "this" {
  count = var.use_default_vpc ? 0 : 1

  vpc_id = aws_vpc.this[0].id
  # ALL, not REJECT. REJECT-only looks cheaper and answers half the question:
  # it cannot distinguish "allowed and the service never replied" from "the
  # packet never left", which is the case that actually costs an afternoon.
  traffic_type         = "ALL"
  log_destination_type = "cloud-watch-logs"
  log_destination      = aws_cloudwatch_log_group.flow[0].arn
  iam_role_arn         = aws_iam_role.flow[0].arn

  tags = merge(var.tags, { Name = "${var.name}-flow-logs" })
}

resource "aws_internet_gateway" "this" {
  count  = var.use_default_vpc ? 0 : 1
  vpc_id = aws_vpc.this[0].id
  tags   = merge(var.tags, { Name = var.name })
}

# The default security group.
#
# Every VPC gets one and it cannot be deleted. Its default rules allow all
# traffic between members, so anything launched without an explicit group lands
# in a permissive one -- the failure is silent because the resource works. Taking
# ownership with no ingress and no egress makes that accident fail closed
# instead.
resource "aws_default_security_group" "this" {
  count = var.use_default_vpc ? 0 : 1

  vpc_id = aws_vpc.this[0].id

  tags = merge(var.tags, { Name = "${var.name}-default-deny" })
}

resource "aws_subnet" "public" {
  for_each = var.use_default_vpc ? {} : { for i, az in var.azs : az => i }

  vpc_id            = aws_vpc.this[0].id
  availability_zone = each.key
  cidr_block        = cidrsubnet(var.cidr, 4, each.value)
  # These subnets exist for the load balancer and the NAT gateways, both of which
  # are given addresses explicitly. Nothing here should get a public IP merely by
  # being launched, and leaving this on means a future instance placed in the
  # wrong subnet is internet-facing by accident rather than by decision.
  map_public_ip_on_launch = false

  tags = merge(var.tags, {
    Name                                        = "${var.name}-public-${each.key}"
    "kubernetes.io/role/elb"                    = "1"
    "kubernetes.io/cluster/${var.cluster_name}" = "shared"
  })
}

resource "aws_subnet" "private" {
  for_each = var.use_default_vpc ? {} : { for i, az in var.azs : az => i }

  vpc_id            = aws_vpc.this[0].id
  availability_zone = each.key
  cidr_block        = cidrsubnet(var.cidr, 4, each.value + 8)
  # Restating the provider default on purpose. "Private" is a name and a route
  # table, not a property the subnet enforces -- flipping this one field is all
  # it takes to make every instance launched here internet-addressable, and a
  # field that is absent cannot be asserted on.
  map_public_ip_on_launch = false
  tags = merge(var.tags, {
    Name                                        = "${var.name}-private-${each.key}"
    "kubernetes.io/role/internal-elb"           = "1"
    "kubernetes.io/cluster/${var.cluster_name}" = "shared"
  })
}

# One NAT gateway per availability zone, not one shared.
#
# A single NAT is the common cost saving and it quietly converts an AZ failure
# into a total egress outage: every private subnet routes through one AZ. It also
# makes all cross-AZ egress a chargeable data-transfer hop, so the saving is
# smaller than it looks. Tier S sidesteps the argument entirely by having no
# private subnets -- gap-register row 2.
resource "aws_eip" "nat" {
  # checkov:skip=CKV2_AWS_19:These EIPs are attached to NAT gateways, not EC2
  #   instances -- see aws_nat_gateway.this below, which consumes each one by
  #   allocation_id. The check only recognises EC2 attachment.
  #   Worth naming the second reason separately: this check is *nondeterministic*.
  #   Four identical runs over an unchanged tree returned 0, 1, 0 and 0 failures,
  #   and an earlier pair returned 3 and 1. It is a graph check over a for_each
  #   set and the resolution appears order-dependent. A gate that disagrees with
  #   itself is worse than one that is merely wrong, because the fix a team learns
  #   is "re-run it", and that habit is then applied to the real failures too.
  #   Skipping it makes the suite honest; gap S47 records what was lost.
  for_each = var.use_default_vpc ? toset([]) : toset(var.azs)
  domain   = "vpc"
  tags     = merge(var.tags, { Name = "${var.name}-nat-${each.key}" })
}

resource "aws_nat_gateway" "this" {
  for_each = var.use_default_vpc ? toset([]) : toset(var.azs)

  allocation_id = aws_eip.nat[each.key].id
  subnet_id     = aws_subnet.public[each.key].id
  tags          = merge(var.tags, { Name = "${var.name}-${each.key}" })

  depends_on = [aws_internet_gateway.this]
}

resource "aws_route_table" "public" {
  count  = var.use_default_vpc ? 0 : 1
  vpc_id = aws_vpc.this[0].id

  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.this[0].id
  }

  tags = merge(var.tags, { Name = "${var.name}-public" })
}

resource "aws_route_table" "private" {
  for_each = var.use_default_vpc ? toset([]) : toset(var.azs)
  vpc_id   = aws_vpc.this[0].id

  route {
    cidr_block     = "0.0.0.0/0"
    nat_gateway_id = aws_nat_gateway.this[each.key].id
  }

  tags = merge(var.tags, { Name = "${var.name}-private-${each.key}" })
}

resource "aws_route_table_association" "public" {
  for_each       = var.use_default_vpc ? toset([]) : toset(var.azs)
  subnet_id      = aws_subnet.public[each.key].id
  route_table_id = aws_route_table.public[0].id
}

resource "aws_route_table_association" "private" {
  for_each       = var.use_default_vpc ? toset([]) : toset(var.azs)
  subnet_id      = aws_subnet.private[each.key].id
  route_table_id = aws_route_table.private[each.key].id
}

# Gateway endpoints for DynamoDB and S3. These are free, and they keep the two
# highest-volume data paths off the NAT gateway entirely -- on this workload that
# is most of the egress bill, not a rounding error.
resource "aws_vpc_endpoint" "gateway" {
  for_each = var.use_default_vpc ? toset([]) : toset(["dynamodb", "s3"])

  vpc_id            = aws_vpc.this[0].id
  service_name      = "com.amazonaws.${var.region}.${each.key}"
  vpc_endpoint_type = "Gateway"
  route_table_ids   = [for rt in aws_route_table.private : rt.id]

  tags = merge(var.tags, { Name = "${var.name}-${each.key}" })
}
