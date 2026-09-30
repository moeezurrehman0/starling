# Tier S -- the KodeKloud playground.
#
# Everything here is shaped by three facts: the session lasts 180 minutes, IAM is
# mostly read-only, and the instance ceiling is 3 x t3.medium. The resulting
# configuration is not what production should look like, and the gap between this
# file and envs/prod/main.tf is the actual deliverable.

locals {
  tags = {
    Project     = "starling"
    Environment = "sandbox"
    ManagedBy   = "terraform"
    # The playground reaps by tag in some configurations, and an untagged
    # resource is the one that survives to bill somebody.
    Ephemeral = "true"
  }
}

module "network" {
  source = "../../modules/network"

  name         = var.name
  cluster_name = var.name
  region       = var.region

  # Adopt the default VPC. Creating one costs a NAT gateway per AZ -- about $0.045
  # an hour each plus data processing -- for infrastructure that is deleted before
  # the first bill. The cost is that nodes sit in public subnets, which is
  # gap-register row 2.
  use_default_vpc = true

  tags = local.tags
}

module "dynamodb" {
  source = "../../modules/dynamodb"

  definitions_file = "${path.module}/../../../../tools/dynamodb-tables.json"
  table_prefix     = "${var.name}-"

  # PITR is billed per GB of continuous backup and is meaningless for data that
  # will not outlive the afternoon.
  point_in_time_recovery = false
  deletion_protection    = false

  tags = local.tags
}

module "storage" {
  source = "../../modules/storage"

  name = var.name

  # force_destroy, because a bucket with objects in it blocks `terraform destroy`,
  # and a blocked destroy in a disposable account means the resources are simply
  # abandoned rather than removed.
  force_destroy = true
  versioning    = false

  cors_origins = ["http://localhost:3000"]

  tags = local.tags
}

module "registry" {
  source = "../../modules/registry"

  name     = var.name
  services = var.services

  force_delete = true

  tags = local.tags
}

module "eks" {
  source = "../../modules/eks"
  count  = var.enable_eks ? 1 : 0

  name            = var.name
  subnet_ids      = module.network.public_subnet_ids
  node_subnet_ids = module.network.node_subnet_ids

  # Adopt, do not create.
  create_cluster_role       = false
  existing_cluster_role_arn = var.cluster_role_arn
  create_node_role          = false
  existing_node_role_arn    = var.node_role_arn

  # logs:CreateLogGroup is frequently denied; EKS will create it itself with
  # never-expire retention, which is fine for an account that does not survive.
  create_log_group = false

  # iam:CreateOpenIDConnectProvider is the usual denial. Without it there is no
  # IRSA at all.
  create_oidc_provider = var.enable_irsa

  # There is no bastion and no VPN into the playground VPC, so the API server has
  # to be reachable from the internet for kubectl to work at all. In Tier P this
  # is false. Gap-register row.
  endpoint_public_access = true

  # Audit logging only. The full set multiplies CloudWatch ingestion on an account
  # with a low default quota, and the others answer questions nobody asks in 180
  # minutes.
  enabled_log_types = ["audit"]

  instance_types = ["t3.medium"]
  desired_size   = 3
  min_size       = 2
  max_size       = 3

  # No ebs-csi-driver: it needs an IRSA role this account cannot create, and
  # nothing here claims a PersistentVolume.
  addons = {
    vpc-cni    = {}
    coredns    = {}
    kube-proxy = {}
  }

  tags = local.tags
}

module "search_db" {
  source = "../../modules/database"
  count  = var.enable_search_db ? 1 : 0

  name       = var.name
  vpc_id     = module.network.vpc_id
  subnet_ids = module.network.node_subnet_ids

  allowed_security_group_ids = var.enable_eks ? [module.eks[0].cluster_security_group_id] : []

  instance_class = "db.t3.micro"
  # gp2, not gp3: free tier covers gp2 only.
  storage_type      = "gp2"
  allocated_storage = 20
  multi_az          = false

  # No backups, no final snapshot, no deletion protection. All three are correct
  # here for the same reason: this database holds a derived index, and a snapshot
  # would outlive the account that can read it.
  backup_retention_days = 0
  skip_final_snapshot   = true
  deletion_protection   = false
  apply_immediately     = true

  # Not available on db.t3.micro; requesting it fails the apply.
  performance_insights = false

  # Secrets Manager has a 7-day minimum deletion window, so a destroy-and-reapply
  # inside one session collides with a secret that is scheduled for deletion and
  # cannot be recreated under the same name. The password comes out of the
  # (gitignored, local) state instead.
  use_secrets_manager = false

  log_exports = []

  tags = local.tags
}

module "irsa" {
  source = "../../modules/iam"
  count  = var.enable_irsa && var.enable_eks ? 1 : 0

  name      = var.name
  namespace = var.namespace
  region    = var.region

  oidc_provider_arn = module.eks[0].oidc_provider_arn
  oidc_provider_url = module.eks[0].oidc_issuer_url

  table_prefix     = "${var.name}-"
  media_bucket_arn = module.storage.bucket_arn

  tags = local.tags
}

# ---------------------------------------------------------------------------
# The fallback, for the session where the playground denies IRSA
# ---------------------------------------------------------------------------
#
# Not a convenience. Until ADR-0014 this repository *claimed* a node-role
# fallback in two places and implemented it in none -- the node role carries
# three managed EKS/ECR policies and nothing that touches DynamoDB or S3. A
# documented fallback that does not exist is worse than no fallback, because it
# is discovered at the moment it is needed.
#
# The grant is deliberately coarse, and deliberately not per-service: that is
# what "the node role" means, and pretending otherwise by splitting it would
# hide the cost. Every pod scheduled on the node holds these permissions,
# including a web frontend that should hold none.

data "aws_partition" "current" {}
data "aws_caller_identity" "current" {}

# Two ways for a pod to reach AWS, and exactly one of them may be in effect.
# Both at once is not additive, it is ambiguous: the SDK would resolve the web
# identity and the isolation the IRSA roles exist to provide would be silently
# undone by the node grant sitting underneath it.
check "one_credential_path" {
  assert {
    condition     = !(var.enable_irsa && var.enable_node_role_fallback)
    error_message = "enable_irsa and enable_node_role_fallback are mutually exclusive: the node grant would undo the per-service isolation IRSA exists to provide."
  }
}

locals {
  fallback_table_arns = [
    for n in values(module.dynamodb.table_names) :
    "arn:${data.aws_partition.current.partition}:dynamodb:${var.region}:${data.aws_caller_identity.current.account_id}:table/${n}"
  ]
}

resource "aws_iam_role_policy" "node_fallback" {
  count = var.enable_node_role_fallback && var.enable_eks ? 1 : 0

  name = "${var.name}-node-application-fallback"
  role = module.eks[0].node_role_name

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "TablesAndTheirIndexes"
        Effect = "Allow"
        Action = [
          "dynamodb:GetItem",
          "dynamodb:BatchGetItem",
          "dynamodb:Query",
          "dynamodb:Scan",
          "dynamodb:DescribeTable",
          "dynamodb:ConditionCheckItem",
          "dynamodb:PutItem",
          "dynamodb:UpdateItem",
          "dynamodb:DeleteItem",
          "dynamodb:BatchWriteItem",
          "dynamodb:TransactWriteItems",
          "dynamodb:TransactGetItems",
        ]
        # Indexes are addressed as a sub-resource of the table and are not
        # covered by the table ARN alone. Omitting them fails only on the first
        # GSI query, which is to say during the demo.
        Resource = concat(
          local.fallback_table_arns,
          [for a in local.fallback_table_arns : "${a}/index/*"],
        )
      },
      {
        Sid      = "Streams"
        Effect   = "Allow"
        Action   = ["dynamodb:DescribeStream", "dynamodb:GetRecords", "dynamodb:GetShardIterator"]
        Resource = [for a in local.fallback_table_arns : "${a}/stream/*"]
      },
      {
        Sid    = "ListStreams"
        Effect = "Allow"
        # ListStreams does not accept a resource-level constraint; AWS rejects a
        # policy that gives it one. The wildcard is the API's, not a shortcut.
        Action   = ["dynamodb:ListStreams"]
        Resource = "*"
      },
      {
        Sid      = "MediaObjects"
        Effect   = "Allow"
        Action   = ["s3:GetObject", "s3:PutObject", "s3:DeleteObject"]
        Resource = "${module.storage.bucket_arn}/*"
      },
      {
        Sid      = "MediaBucket"
        Effect   = "Allow"
        Action   = ["s3:ListBucket", "s3:GetBucketLocation"]
        Resource = module.storage.bucket_arn
      },
    ]
  })
}
