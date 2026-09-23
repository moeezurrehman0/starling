# IRSA -- one role per service, scoped to the tables that service actually uses.
#
# The grants below are derived from what the code does, not from what looked
# convenient: user-service touches users/handles/credentials/follows,
# tweet-service touches tweets/likes/idempotency, and so on. The point of the
# exercise is that a compromised tweet-service cannot read credentials -- which
# is precisely the property the sandbox cannot demonstrate, because without an
# OIDC provider every pod falls back to the node instance role.

data "aws_caller_identity" "current" {}
data "aws_partition" "current" {}

locals {
  account_id = data.aws_caller_identity.current.account_id
  partition  = data.aws_partition.current.partition

  # The OIDC issuer URL minus its scheme. The trust policy's condition keys are
  # built from this bare host+path, while the Federated principal needs the full
  # provider ARN -- mixing the two produces a role that assumes cleanly in the
  # console and fails in the cluster.
  oidc_host = replace(var.oidc_provider_url, "https://", "")

  read_actions = [
    "dynamodb:GetItem",
    "dynamodb:BatchGetItem",
    "dynamodb:Query",
    "dynamodb:Scan",
    "dynamodb:DescribeTable",
    "dynamodb:ConditionCheckItem",
  ]

  write_actions = [
    "dynamodb:PutItem",
    "dynamodb:UpdateItem",
    "dynamodb:DeleteItem",
    "dynamodb:BatchWriteItem",
  ]

  stream_actions = [
    "dynamodb:DescribeStream",
    "dynamodb:GetRecords",
    "dynamodb:GetShardIterator",
  ]

  # Flatten to a set of role names that actually need a policy. A service with no
  # grants still gets a role -- the chart annotates every service account the same
  # way, and a role with no policy is a clearer statement of "this needs nothing"
  # than an absent one.
  services_with_policy = {
    for name, s in var.services : name => s
    if length(s.tables_read) > 0 || length(s.tables_write) > 0 || length(s.table_streams) > 0 || s.media_bucket_access != "none"
  }
}

data "aws_iam_policy_document" "assume" {
  for_each = var.services

  statement {
    effect  = "Allow"
    actions = ["sts:AssumeRoleWithWebIdentity"]

    principals {
      type        = "Federated"
      identifiers = [var.oidc_provider_arn]
    }

    # Both conditions are required. Without :sub the role is assumable by any
    # service account in the cluster; without :aud it is assumable by any token
    # the issuer signs, including ones minted for other audiences. Dropping
    # either one turns per-service isolation into cluster-wide access while
    # everything still works.
    condition {
      test     = "StringEquals"
      variable = "${local.oidc_host}:sub"
      values   = ["system:serviceaccount:${var.namespace}:${each.key}"]
    }

    condition {
      test     = "StringEquals"
      variable = "${local.oidc_host}:aud"
      values   = ["sts.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "this" {
  for_each = var.services

  name               = "${var.name}-${each.key}"
  description        = "IRSA role for ${each.key} in namespace ${var.namespace}"
  assume_role_policy = data.aws_iam_policy_document.assume[each.key].json

  # An hour. Long enough that a pod is not re-assuming constantly, short enough
  # that a leaked credential expires within a shift.
  max_session_duration = 3600

  tags = merge(var.tags, { Name = "${var.name}-${each.key}", Service = each.key })
}

data "aws_iam_policy_document" "access" {
  for_each = local.services_with_policy

  dynamic "statement" {
    for_each = length(each.value.tables_read) > 0 ? [1] : []
    content {
      sid     = "TableRead"
      effect  = "Allow"
      actions = local.read_actions
      # Both the table and its indexes. A Query against a GSI is authorised
      # against the index ARN, not the table's -- granting only the table
      # produces an AccessDenied that names an ARN the policy appears to contain.
      resources = flatten([
        for t in each.value.tables_read : [
          "arn:${local.partition}:dynamodb:${var.region}:${local.account_id}:table/${var.table_prefix}${t}",
          "arn:${local.partition}:dynamodb:${var.region}:${local.account_id}:table/${var.table_prefix}${t}/index/*",
        ]
      ])
    }
  }

  dynamic "statement" {
    for_each = length(each.value.tables_write) > 0 ? [1] : []
    content {
      sid       = "TableWrite"
      effect    = "Allow"
      actions   = local.write_actions
      resources = [for t in each.value.tables_write : "arn:${local.partition}:dynamodb:${var.region}:${local.account_id}:table/${var.table_prefix}${t}"]
    }
  }

  dynamic "statement" {
    for_each = length(each.value.table_streams) > 0 ? [1] : []
    content {
      sid       = "StreamRead"
      effect    = "Allow"
      actions   = local.stream_actions
      resources = [for t in each.value.table_streams : "arn:${local.partition}:dynamodb:${var.region}:${local.account_id}:table/${var.table_prefix}${t}/stream/*"]
    }
  }

  # ListStreams takes no resource -- IAM rejects anything but "*" for it. It is
  # separated rather than folded into the statement above so that the wildcard is
  # visibly deliberate and nobody "tidies" the stream grant by widening it.
  dynamic "statement" {
    for_each = length(each.value.table_streams) > 0 ? [1] : []
    content {
      sid       = "ListStreams"
      effect    = "Allow"
      actions   = ["dynamodb:ListStreams"]
      resources = ["*"]
    }
  }

  dynamic "statement" {
    for_each = each.value.media_bucket_access == "none" ? [] : [each.value.media_bucket_access]
    content {
      sid    = "MediaObjects"
      effect = "Allow"
      actions = concat(
        ["s3:GetObject"],
        statement.value == "rw" ? ["s3:PutObject", "s3:DeleteObject", "s3:AbortMultipartUpload"] : [],
      )
      resources = ["${var.media_bucket_arn}/*"]
    }
  }

  dynamic "statement" {
    for_each = each.value.media_bucket_access == "none" ? [] : [1]
    content {
      sid       = "MediaBucket"
      effect    = "Allow"
      actions   = ["s3:ListBucket", "s3:GetBucketLocation"]
      resources = [var.media_bucket_arn]
    }
  }
}

resource "aws_iam_policy" "access" {
  for_each = local.services_with_policy

  name        = "${var.name}-${each.key}"
  description = "Least-privilege data access for ${each.key}"
  policy      = data.aws_iam_policy_document.access[each.key].json
  tags        = var.tags
}

resource "aws_iam_role_policy_attachment" "access" {
  for_each = local.services_with_policy

  role       = aws_iam_role.this[each.key].name
  policy_arn = aws_iam_policy.access[each.key].arn
}
