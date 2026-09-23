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
  count = var.use_default_vpc ? 0 : 1

  cidr_block = var.cidr
  # Both required by EKS. Without DNS hostnames the kubelet cannot resolve the
  # API endpoint through a VPC endpoint, and the nodes fail to join with a TLS
  # error that names neither DNS nor the endpoint.
  enable_dns_support   = true
  enable_dns_hostnames = true

  tags = merge(var.tags, { Name = var.name })
}

resource "aws_internet_gateway" "this" {
  count  = var.use_default_vpc ? 0 : 1
  vpc_id = aws_vpc.this[0].id
  tags   = merge(var.tags, { Name = var.name })
}

resource "aws_subnet" "public" {
  for_each = var.use_default_vpc ? {} : { for i, az in var.azs : az => i }

  vpc_id                  = aws_vpc.this[0].id
  availability_zone       = each.key
  cidr_block              = cidrsubnet(var.cidr, 4, each.value)
  map_public_ip_on_launch = true

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
