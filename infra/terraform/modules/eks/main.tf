# EKS — cluster, one managed node group, and the OIDC provider that makes IRSA work.
#
# The tier split here is not cosmetic. The KodeKloud playground pre-creates a role
# named `eksClusterRole` and forbids creating new IAM roles, so Tier S adopts what
# exists and Tier P creates what it needs. Everything downstream -- IRSA, the node
# role, the addon roles -- inherits that split, and the diff between the two is
# one of the more honest rows in the gap register.

data "aws_partition" "current" {}

locals {
  cluster_role_arn = var.create_cluster_role ? aws_iam_role.cluster[0].arn : var.existing_cluster_role_arn
  node_role_arn    = var.create_node_role ? aws_iam_role.node[0].arn : var.existing_node_role_arn
  arn_prefix       = "arn:${data.aws_partition.current.partition}:iam::aws:policy"
}

# ---------------------------------------------------------------------------
# Cluster role
# ---------------------------------------------------------------------------

data "aws_iam_policy_document" "cluster_assume" {
  count = var.create_cluster_role ? 1 : 0

  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["eks.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "cluster" {
  count = var.create_cluster_role ? 1 : 0

  name               = "${var.name}-cluster"
  assume_role_policy = data.aws_iam_policy_document.cluster_assume[0].json
  tags               = var.tags
}

resource "aws_iam_role_policy_attachment" "cluster" {
  for_each = var.create_cluster_role ? toset(["AmazonEKSClusterPolicy"]) : toset([])

  role       = aws_iam_role.cluster[0].name
  policy_arn = "${local.arn_prefix}/${each.value}"
}

# ---------------------------------------------------------------------------
# Control plane
# ---------------------------------------------------------------------------

resource "aws_cloudwatch_log_group" "cluster" {
  count = var.create_log_group ? 1 : 0

  # EKS creates this group itself on first log delivery, with never-expire
  # retention. Creating it here is the only way to bound retention -- and it must
  # exist before the cluster, or EKS wins the race and Terraform then fails on an
  # already-exists it did not create.
  name              = "/aws/eks/${var.name}/cluster"
  retention_in_days = var.log_retention_days
  # Control-plane logs contain the full audit trail: every authenticated request,
  # including who read which Secret. Encrypting them with the same customer-managed
  # key as etcd means revoking that key revokes access to both the Secrets and the
  # record of who touched them, rather than leaving the audit log readable.
  kms_key_id = var.log_kms_key_arn
  tags       = var.tags
}

resource "aws_eks_cluster" "this" {
  name     = var.name
  role_arn = local.cluster_role_arn
  version  = var.kubernetes_version

  vpc_config {
    subnet_ids = var.subnet_ids
    # Private access on means in-VPC traffic reaches the API server without
    # leaving the VPC. Public access stays on in Tier S because there is no
    # bastion and no VPN; Tier P narrows it with public_access_cidrs and
    # ultimately turns it off. Gap-register row.
    endpoint_private_access = true
    endpoint_public_access  = var.endpoint_public_access
    public_access_cidrs     = var.public_access_cidrs
    security_group_ids      = var.cluster_security_group_ids
  }

  # Audit is the one log type that answers "who did this". The others are cheap
  # enough that leaving them off only saves money you notice during an incident.
  enabled_cluster_log_types = var.enabled_log_types

  access_config {
    # API, not CONFIG_MAP. The aws-auth ConfigMap is a cluster-scoped object with
    # no validation: a typo in a role ARN locks everyone out, and the fix requires
    # the access you just removed. Access entries are an API with server-side
    # validation and cannot lock out the cluster creator.
    authentication_mode                         = "API"
    bootstrap_cluster_creator_admin_permissions = true
  }

  dynamic "encryption_config" {
    for_each = var.secrets_kms_key_arn == null ? [] : [var.secrets_kms_key_arn]
    content {
      provider {
        key_arn = encryption_config.value
      }
      # Envelope encryption for Secrets. Without it, a Secret is base64 in etcd
      # -- which is to say, not encrypted.
      resources = ["secrets"]
    }
  }

  tags = merge(var.tags, { Name = var.name })

  depends_on = [
    aws_iam_role_policy_attachment.cluster,
    aws_cloudwatch_log_group.cluster,
  ]
}

# ---------------------------------------------------------------------------
# OIDC provider -- the whole basis of IRSA
# ---------------------------------------------------------------------------

data "tls_certificate" "oidc" {
  count = var.create_oidc_provider ? 1 : 0
  url   = aws_eks_cluster.this.identity[0].oidc[0].issuer
}

resource "aws_iam_openid_connect_provider" "this" {
  count = var.create_oidc_provider ? 1 : 0

  url             = aws_eks_cluster.this.identity[0].oidc[0].issuer
  client_id_list  = ["sts.amazonaws.com"]
  thumbprint_list = [data.tls_certificate.oidc[0].certificates[0].sha1_fingerprint]

  tags = var.tags
}

# ---------------------------------------------------------------------------
# Node role
# ---------------------------------------------------------------------------

data "aws_iam_policy_document" "node_assume" {
  count = var.create_node_role ? 1 : 0

  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ec2.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "node" {
  count = var.create_node_role ? 1 : 0

  name               = "${var.name}-node"
  assume_role_policy = data.aws_iam_policy_document.node_assume[0].json
  tags               = var.tags
}

resource "aws_iam_role_policy_attachment" "node" {
  # The node role carries exactly these three and nothing application-shaped.
  # Every workload permission goes through IRSA instead -- otherwise any pod on
  # the node inherits the node's rights, which is why the chart's NetworkPolicy
  # blocks 169.254.169.254.
  for_each = var.create_node_role ? toset([
    "AmazonEKSWorkerNodePolicy",
    "AmazonEKS_CNI_Policy",
    "AmazonEC2ContainerRegistryReadOnly",
  ]) : toset([])

  role       = aws_iam_role.node[0].name
  policy_arn = "${local.arn_prefix}/${each.value}"
}

# ---------------------------------------------------------------------------
# Managed node group
# ---------------------------------------------------------------------------

resource "aws_eks_node_group" "this" {
  cluster_name    = aws_eks_cluster.this.name
  node_group_name = "${var.name}-default"
  node_role_arn   = local.node_role_arn
  subnet_ids      = var.node_subnet_ids
  version         = var.kubernetes_version

  instance_types = var.instance_types
  capacity_type  = var.capacity_type
  disk_size      = var.disk_size
  ami_type       = var.ami_type

  scaling_config {
    desired_size = var.desired_size
    min_size     = var.min_size
    max_size     = var.max_size
  }

  update_config {
    # One node at a time. With three t3.mediums, two unavailable leaves a single
    # node that cannot hold the workload, and the rolling update stalls against
    # the PodDisruptionBudgets rather than completing -- which looks like a
    # hanging apply, not a capacity problem.
    max_unavailable = 1
  }

  labels = var.node_labels
  tags   = merge(var.tags, { Name = "${var.name}-default" })

  lifecycle {
    # The cluster autoscaler owns desired_size once it is running. Leaving it
    # under Terraform's control means every plan wants to undo whatever scaling
    # decision was made since the last apply.
    ignore_changes = [scaling_config[0].desired_size]

    precondition {
      condition     = var.desired_size >= var.min_size && var.desired_size <= var.max_size
      error_message = "desired_size must sit between min_size and max_size."
    }
  }

  depends_on = [aws_iam_role_policy_attachment.node]
}

# ---------------------------------------------------------------------------
# Addons
# ---------------------------------------------------------------------------

resource "aws_eks_addon" "this" {
  for_each = var.addons

  cluster_name  = aws_eks_cluster.this.name
  addon_name    = each.key
  addon_version = each.value.version == "" ? null : each.value.version

  service_account_role_arn = each.value.service_account_role_arn

  # PRESERVE would leave an orphaned, unmanaged addon behind on every version
  # bump; OVERWRITE is what "this is declared here" actually means.
  resolve_conflicts_on_create = "OVERWRITE"
  resolve_conflicts_on_update = "OVERWRITE"

  tags = var.tags

  # vpc-cni must be in place before nodes join or they never get an IP, and
  # ebs-csi needs a node to schedule its controller onto. Ordering the whole set
  # after the node group is the simplest correct answer.
  depends_on = [aws_eks_node_group.this]
}
