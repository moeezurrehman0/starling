# DynamoDB — the operational store.
#
# The table definitions are NOT here. They live in tools/dynamodb-tables.json and
# are read by both this module and tools/localstack/create-tables.py, so the
# Compose stack and AWS cannot drift.
#
# That matters more than it sounds. Schema drift between local and deployed has a
# nasty signature: a GSI that exists locally and not in AWS produces a
# ValidationException on the first query that uses it, which is to say after
# deploy, on one code path, under load. Reading both from one file turns that
# class of bug into a merge conflict.
#
# Keys prefixed with `$` are documentation and are ignored here, which is why the
# file is JSON -- jsondecode can read it and Terraform cannot read YAML.

locals {
  definitions = jsondecode(file(var.definitions_file))
  tables      = local.definitions.tables

  # Distinct attribute names across every table, used only by the validation
  # below. DynamoDB requires that every attribute declared is used in a key or an
  # index; declaring a spare is a create-time error rather than a warning.
  billing_mode = "PAY_PER_REQUEST"
}

resource "aws_dynamodb_table" "this" {
  for_each = local.tables

  name = "${var.table_prefix}${each.key}"

  # On-demand, everywhere, deliberately.
  #
  # Provisioned capacity is cheaper at steady state and handles the one access
  # pattern this whole design exists to survive -- a celebrity tweet -- worst of
  # all: the burst arrives in seconds and capacity autoscaling reacts in minutes.
  # It is also the one DynamoDB feature the playground allow-lists without
  # qualification, so the sandbox and production agree here for once.
  billing_mode = local.billing_mode

  hash_key  = each.value.hash_key
  range_key = lookup(each.value, "range_key", null)

  dynamic "attribute" {
    for_each = each.value.attributes
    content {
      name = attribute.key
      type = attribute.value
    }
  }

  dynamic "global_secondary_index" {
    for_each = each.value.global_secondary_indexes
    content {
      name            = global_secondary_index.key
      hash_key        = global_secondary_index.value.hash_key
      range_key       = lookup(global_secondary_index.value, "range_key", null)
      projection_type = global_secondary_index.value.projection
      non_key_attributes = (
        global_secondary_index.value.projection == "INCLUDE"
        ? lookup(global_secondary_index.value, "non_key_attributes", null)
        : null
      )
    }
  }

  dynamic "ttl" {
    for_each = lookup(each.value, "ttl_attribute", null) == null ? [] : [each.value.ttl_attribute]
    content {
      attribute_name = ttl.value
      enabled        = true
    }
  }

  stream_enabled   = lookup(each.value, "stream", null) != null
  stream_view_type = lookup(each.value, "stream", null)

  # Point-in-time recovery is the difference between a bad deploy costing an hour
  # and costing the dataset. Off in the sandbox, where the account is deleted
  # every 180 minutes and PITR would bill per GB for a restore window that cannot
  # outlive the session -- gap-register row 7.
  point_in_time_recovery {
    enabled = var.point_in_time_recovery
  }

  server_side_encryption {
    # The AWS-owned key encrypts at rest and is free. A customer-managed key adds
    # per-request KMS cost and, more usefully, the ability to revoke access to the
    # data independently of any IAM policy. Production uses one; the sandbox
    # cannot, because KMS key creation is a likely denial and a key has a
    # mandatory waiting period before deletion that outlives the session.
    enabled     = var.kms_key_arn != null
    kms_key_arn = var.kms_key_arn
  }

  deletion_protection_enabled = var.deletion_protection

  tags = merge(var.tags, { Name = "${var.table_prefix}${each.key}" })

  lifecycle {
    precondition {
      # Every declared attribute must appear in the key schema or an index, or the
      # create fails with an error that names the attribute but not the reason.
      # Catching it at plan time costs nothing; catching it at apply time costs a
      # partial apply in a 180-minute session.
      condition = length(setsubtract(
        keys(each.value.attributes),
        concat(
          [each.value.hash_key],
          lookup(each.value, "range_key", null) == null ? [] : [each.value.range_key],
          flatten([
            for g in values(each.value.global_secondary_indexes) : concat(
              [g.hash_key],
              lookup(g, "range_key", null) == null ? [] : [g.range_key],
            )
          ]),
        )
      )) == 0
      error_message = "Table ${each.key} declares an attribute used by no key or index; DynamoDB rejects that at create time."
    }
  }
}
