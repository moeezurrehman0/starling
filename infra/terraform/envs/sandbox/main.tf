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
