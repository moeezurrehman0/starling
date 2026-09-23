# Unit tests for the database module -- the Postgres instance that holds the
# search index and nothing else.
#
# The single most important property of this module is that losing it loses
# nothing: DynamoDB is authoritative and the index is rebuildable from it. Most
# of what follows checks that the module is nevertheless not casually destroyable
# in production, because "rebuildable" and "rebuilt during an incident" are
# different things.

# The one property this file cannot express: that the database security group has
# no egress rule at all. `terraform test` can only assert over resources that are
# declared, so the absence of a resource type is invisible to it -- referencing
# aws_vpc_security_group_egress_rule fails to parse rather than evaluating false.
# That assertion lives in scripts/tf-validate.sh, which greps source. Worth
# stating because "the tests pass" otherwise implies a coverage this file does
# not have.

mock_provider "aws" {}
mock_provider "random" {}

variables {
  name       = "twitter-clone"
  vpc_id     = "vpc-aaaaaaaa"
  subnet_ids = ["subnet-aaaaaaaa", "subnet-bbbbbbbb"]
}

run "the_instance_is_private_and_encrypted_by_default" {
  command = plan

  assert {
    condition     = !aws_db_instance.this.publicly_accessible
    error_message = "the search database is publicly accessible"
  }

  assert {
    condition     = aws_db_instance.this.storage_encrypted
    error_message = "storage encryption is off"
  }
}

run "ingress_is_by_security_group_reference_never_cidr" {
  command = plan

  variables {
    allowed_security_group_ids = ["sg-aaaaaaaa"]
  }

  # A CIDR rule keeps matching after the thing it was written for is replaced.
  # A security-group reference follows the workload.
  assert {
    condition = alltrue([
      for r in aws_vpc_security_group_ingress_rule.from_nodes :
      r.cidr_ipv4 == null && r.referenced_security_group_id != null
    ])
    error_message = "database ingress is allowed by CIDR; a CIDR rule outlives the workload it was written for and silently admits whatever occupies that range next"
  }

  assert {
    condition = alltrue([
      for r in aws_vpc_security_group_ingress_rule.from_nodes : r.from_port == 5432 && r.to_port == 5432
    ])
    error_message = "database ingress is not narrowed to 5432"
  }
}

run "production_settings_are_reachable_through_variables" {
  command = plan

  variables {
    multi_az              = true
    deletion_protection   = true
    skip_final_snapshot   = false
    backup_retention_days = 14
  }

  assert {
    condition     = aws_db_instance.this.multi_az
    error_message = "multi_az = true did not reach the instance"
  }

  assert {
    condition     = aws_db_instance.this.deletion_protection
    error_message = "deletion_protection = true did not reach the instance"
  }

  assert {
    condition     = aws_db_instance.this.backup_retention_period == 14
    error_message = "backup retention did not reach the instance"
  }
}
