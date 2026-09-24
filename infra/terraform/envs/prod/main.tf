# Tier P -- production as code, never applied.
#
# This root exists to be read and diffed, not run. Every difference from
# envs/sandbox/main.tf is a control the playground cannot express, and enumerating
# them honestly is more useful than pretending a 180-minute account demonstrates
# production practice.
#
# Read it alongside envs/sandbox/main.tf. The differences, in order of how much
# they matter:
#
#   1. Private nodes behind NAT, not a default VPC's public subnets.
#   2. A private API endpoint, reachable only from named CIDRs.
#   3. Customer-managed KMS everywhere, including etcd envelope encryption.
#   4. Per-service IRSA roles, so a compromised pod cannot read another's tables.
#   5. PITR, backups, deletion protection and final snapshots all on.
#   6. Remote, locked, encrypted state instead of a gitignored local file.

locals {
  tags = {
    Project     = "starling"
    Environment = "production"
    ManagedBy   = "terraform"
    # A cost-allocation tag that is applied from day one, because retrofitting
    # tags onto live infrastructure means a rolling replacement of everything
    # that does not support in-place tagging.
    CostCentre = "platform"
  }
}

# ---------------------------------------------------------------------------
# Keys
# ---------------------------------------------------------------------------

resource "aws_kms_key" "data" {
  description = "starling production data at rest"
  # Rotation is annual and free; the reason to enable it is not the rotation, it
  # is that the key can be rotated at all without re-encrypting anything.
  enable_key_rotation     = true
  deletion_window_in_days = 30
  tags                    = local.tags
}

resource "aws_kms_alias" "data" {
  name          = "alias/${var.name}-data"
  target_key_id = aws_kms_key.data.key_id
}

# A separate key for etcd. Same reasoning as separate roles: one compromised key
# policy should not expose both the database and every Kubernetes Secret.
resource "aws_kms_key" "secrets" {
  description             = "starling EKS secrets envelope encryption"
  enable_key_rotation     = true
  deletion_window_in_days = 30
  tags                    = local.tags
}

resource "aws_kms_alias" "secrets" {
  name          = "alias/${var.name}-secrets"
  target_key_id = aws_kms_key.secrets.key_id
}

# ---------------------------------------------------------------------------
# Network
# ---------------------------------------------------------------------------

module "network" {
  source = "../../modules/network"

  name         = var.name
  cluster_name = var.name
  region       = var.region

  # Purpose-built VPC: private node subnets, one NAT per AZ, gateway endpoints for
  # DynamoDB and S3. The per-AZ NAT is the expensive choice and the correct one --
  # a single shared NAT makes an AZ failure a total egress failure, and the saving
  # is smaller than one incident.
  use_default_vpc = false
  cidr            = var.vpc_cidr
  azs             = var.azs

  tags = local.tags
}

# ---------------------------------------------------------------------------
# Data
# ---------------------------------------------------------------------------

module "dynamodb" {
  source = "../../modules/dynamodb"

  definitions_file = "${path.module}/../../../../tools/dynamodb-tables.json"
  table_prefix     = "${var.name}-"

  point_in_time_recovery = true
  deletion_protection    = true
  kms_key_arn            = aws_kms_key.data.arn

  tags = local.tags
}

module "storage" {
  source = "../../modules/storage"

  name = var.name

  force_destroy = false
  versioning    = true
  kms_key_arn   = aws_kms_key.data.arn
  cors_origins  = var.web_origins

  tags = local.tags
}

module "registry" {
  source = "../../modules/registry"

  name        = var.name
  services    = var.services
  kms_key_arn = aws_kms_key.data.arn

  # A repository that still holds images is a repository something might still be
  # running. Destroy should fail.
  force_delete = false

  tags = local.tags
}

# ---------------------------------------------------------------------------
# Cluster
# ---------------------------------------------------------------------------

module "eks" {
  source = "../../modules/eks"

  name            = var.name
  subnet_ids      = module.network.node_subnet_ids
  node_subnet_ids = module.network.node_subnet_ids

  create_cluster_role  = true
  create_node_role     = true
  create_oidc_provider = true
  create_log_group     = true
  log_retention_days   = 90

  # The API server is not on the internet. Reaching it requires being inside the
  # VPC or on a named egress range -- which is what makes a leaked kubeconfig an
  # inconvenience rather than an incident.
  endpoint_public_access = false
  public_access_cidrs    = var.api_access_cidrs

  secrets_kms_key_arn = aws_kms_key.secrets.arn

  instance_types = ["m6i.large"]
  desired_size   = 3
  min_size       = 3
  max_size       = 9

  addons = {
    vpc-cni    = {}
    coredns    = {}
    kube-proxy = {}
    # Needs its own IRSA role; wiring it is left with the cluster-addon roles
    # rather than the application ones.
    aws-ebs-csi-driver = {}
  }

  tags = local.tags
}

module "search_db" {
  source = "../../modules/database"

  name       = var.name
  vpc_id     = module.network.vpc_id
  subnet_ids = module.network.node_subnet_ids

  allowed_security_group_ids = [module.eks.cluster_security_group_id]

  instance_class    = "db.m6g.large"
  storage_type      = "gp3"
  allocated_storage = 100

  # Multi-AZ even though the data is derived. Not for durability -- for the
  # failover: a single-AZ replacement is a 10-minute reindex during which search
  # returns nothing, and "search is down" reads as "the site is broken".
  multi_az = true

  backup_retention_days = 7
  skip_final_snapshot   = false
  deletion_protection   = true
  performance_insights  = true
  kms_key_arn           = aws_kms_key.data.arn

  use_secrets_manager  = true
  secret_recovery_days = 30

  tags = local.tags
}

module "irsa" {
  source = "../../modules/iam"

  name      = var.name
  namespace = var.namespace
  region    = var.region

  oidc_provider_arn = module.eks.oidc_provider_arn
  oidc_provider_url = module.eks.oidc_issuer_url

  table_prefix     = "${var.name}-"
  media_bucket_arn = module.storage.bucket_arn

  tags = local.tags
}
