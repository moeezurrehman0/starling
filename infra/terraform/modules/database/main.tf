# RDS Postgres — the search index, and nothing else.
#
# This database holds no authoritative data. Every row in it is derived from the
# tweets table by the search indexer, and losing the whole instance costs a
# reindex, not a restore. That single fact is what makes a single-AZ db.t3.micro
# defensible in the sandbox and what keeps the blast radius of this module small.
#
# It is the reason the design uses DynamoDB for everything else: full-text search
# is the one access pattern DynamoDB genuinely cannot serve, and the answer is a
# purpose-built index alongside it rather than bending the primary store.

resource "random_password" "master" {
  length = 32
  # RDS rejects '/', '@', '"' and space in a master password, and the error names
  # the parameter rather than the character -- so a generated password fails the
  # apply roughly one time in three with a message that looks like a bug.
  special          = true
  override_special = "!#$%&*()-_=+[]{}<>:?"
}

resource "aws_secretsmanager_secret" "master" {
  count = var.use_secrets_manager ? 1 : 0

  name = "${var.name}/search-db"
  # Zero is not allowed; seven is the minimum. In a sandbox even seven days
  # outlives the account, so a re-apply after a destroy collides with the
  # scheduled-for-deletion secret and fails on a name that appears unused. That is
  # exactly why Tier S sets use_secrets_manager = false -- gap-register row 8.
  recovery_window_in_days = var.secret_recovery_days

  tags = var.tags
}

resource "aws_secretsmanager_secret_version" "master" {
  count = var.use_secrets_manager ? 1 : 0

  secret_id = aws_secretsmanager_secret.master[0].id
  secret_string = jsonencode({
    username = var.master_username
    password = random_password.master.result
    dbname   = var.database_name
    host     = aws_db_instance.this.address
    port     = aws_db_instance.this.port
  })
}

resource "aws_db_subnet_group" "this" {
  name       = "${var.name}-search"
  subnet_ids = var.subnet_ids
  tags       = merge(var.tags, { Name = "${var.name}-search" })
}

resource "aws_security_group" "this" {
  name        = "${var.name}-search-db"
  description = "Postgres access for the search index"
  vpc_id      = var.vpc_id

  tags = merge(var.tags, { Name = "${var.name}-search-db" })
}

# Ingress from the cluster's node security group only, never a CIDR. A CIDR rule
# is correct on the day it is written and wrong the moment the VPC is resized;
# referencing the security group keeps it correct by construction.
resource "aws_vpc_security_group_ingress_rule" "from_nodes" {
  for_each = toset(var.allowed_security_group_ids)

  security_group_id            = aws_security_group.this.id
  referenced_security_group_id = each.value
  from_port                    = 5432
  to_port                      = 5432
  ip_protocol                  = "tcp"
  description                  = "Postgres from the EKS nodes"
}

# No egress rules at all. A database does not initiate connections, and the
# default "allow all egress" that AWS attaches to a new security group is the
# quiet half of most exfiltration paths.

resource "aws_db_instance" "this" {
  identifier = "${var.name}-search"

  engine         = "postgres"
  engine_version = var.engine_version
  instance_class = var.instance_class

  allocated_storage = var.allocated_storage
  # gp3 everywhere it is allowed: same price as gp2 at these sizes and it
  # decouples IOPS from volume size, so a 20 GB volume is not also capped at 60
  # baseline IOPS. The sandbox is pinned to gp2 by the free-tier constraint.
  storage_type      = var.storage_type
  storage_encrypted = true
  kms_key_id        = var.kms_key_arn

  db_name  = var.database_name
  username = var.master_username
  password = random_password.master.result

  db_subnet_group_name   = aws_db_subnet_group.this.name
  vpc_security_group_ids = [aws_security_group.this.id]
  # Never. Even in the sandbox: a publicly addressable Postgres with a generated
  # password is found by scanners in minutes, and the playground account is a real
  # AWS account.
  publicly_accessible = false

  multi_az                = var.multi_az
  backup_retention_period = var.backup_retention_days
  skip_final_snapshot     = var.skip_final_snapshot
  final_snapshot_identifier = (
    var.skip_final_snapshot ? null : "${var.name}-search-final-${formatdate("YYYYMMDDhhmmss", timestamp())}"
  )
  deletion_protection = var.deletion_protection

  auto_minor_version_upgrade = true
  apply_immediately          = var.apply_immediately

  # Postgres logs to CloudWatch, because an instance that has been replaced takes
  # its local logs with it -- and the interesting logs are usually the ones from
  # just before the replacement.
  enabled_cloudwatch_logs_exports = var.log_exports

  performance_insights_enabled = var.performance_insights

  tags = merge(var.tags, { Name = "${var.name}-search" })

  lifecycle {
    ignore_changes = [
      # formatdate(timestamp()) changes on every plan, so without this the
      # instance shows a permanent diff and eventually somebody applies it.
      final_snapshot_identifier,
    ]
  }
}
