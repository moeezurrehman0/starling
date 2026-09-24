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
  # checkov:skip=CKV2_AWS_57:Automatic rotation needs a rotation Lambda with VPC
  #   access to the database, which is a real component with its own failure mode
  #   -- a broken rotator locks the application out of its own database at 3am.
  #   This root is never applied, so shipping an untested rotator would be a
  #   worse lie than declaring the gap. Tracked in docs/16-gap-register.md.
  count = var.use_secrets_manager ? 1 : 0

  name = "${var.name}/search-db"
  # Zero is not allowed; seven is the minimum. In a sandbox even seven days
  # outlives the account, so a re-apply after a destroy collides with the
  # scheduled-for-deletion secret and fails on a name that appears unused. That is
  # exactly why Tier S sets use_secrets_manager = false -- gap-register row 8.
  recovery_window_in_days = var.secret_recovery_days

  # The default is an AWS-managed key shared by every secret in the account, which
  # means "who can read this password" is answerable only through IAM. A dedicated
  # key makes it answerable by reading the key policy, and revocable without
  # touching IAM at all.
  kms_key_id = var.kms_key_arn

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
  # Keyed by position, not by the security group id itself.
  #
  # toset(var.allowed_security_group_ids) reads better and cannot plan: the
  # caller passes module.eks.cluster_security_group_id, which does not exist
  # until the cluster is created, so the for_each keys are unknown and Terraform
  # refuses. The failure is specific to a clean apply -- once the group is in
  # state the id is known and every subsequent plan succeeds, so this only ever
  # breaks the first apply into a new account, which in a 180-minute session is
  # the only apply there is.
  #
  # The list is ordered and short, so positional keys are stable; the id is still
  # the value, it is simply no longer also the address.
  for_each = { for idx, sg in var.allowed_security_group_ids : tostring(idx) => sg }

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

# Query logging.
#
# log_min_duration_statement over log_statement = 'all': logging every statement
# on a search database is both a performance tax and a privacy problem, because
# the statements contain what users typed. One second captures the queries worth
# investigating and ignores the millions that are fine.
resource "aws_db_parameter_group" "this" {
  name   = "${var.name}-search"
  family = var.parameter_group_family

  parameter {
    name  = "log_min_duration_statement"
    value = tostring(var.slow_query_threshold_ms)
  }

  # Refuse unencrypted connections outright. Postgres will happily negotiate
  # plaintext if the client asks, and every Postgres client library defaults to
  # "use TLS if offered, otherwise don't" -- so without this the connection is
  # encrypted only because nothing went wrong, and a misconfigured client
  # downgrades silently rather than failing.
  parameter {
    name  = "rds.force_ssl"
    value = "1"
  }

  parameter {
    name  = "log_connections"
    value = "1"
  }

  parameter {
    name  = "log_disconnections"
    value = "1"
  }

  # Without this the log line records the statement but not which database, user
  # or client issued it, which makes it evidence of a problem rather than a lead.
  parameter {
    name  = "log_line_prefix"
    value = "%t:%r:%u@%d:[%p]:"
  }

  tags = var.tags

  lifecycle {
    create_before_destroy = true
  }
}

data "aws_iam_policy_document" "monitoring_assume" {
  count = var.monitoring_interval > 0 ? 1 : 0

  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["monitoring.rds.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "monitoring" {
  count = var.monitoring_interval > 0 ? 1 : 0

  name               = "${var.name}-rds-monitoring"
  assume_role_policy = data.aws_iam_policy_document.monitoring_assume[0].json
  tags               = var.tags
}

resource "aws_iam_role_policy_attachment" "monitoring" {
  count = var.monitoring_interval > 0 ? 1 : 0

  role       = aws_iam_role.monitoring[0].name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonRDSEnhancedMonitoringRole"
}

resource "aws_db_instance" "this" {
  identifier     = "${var.name}-search"
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
  # Snapshots inherit the instance's tags. Without this a restored database is
  # untagged, which means it is invisible to cost allocation and -- the reason
  # it matters here -- invisible to the teardown sweep, which finds resources by
  # tag. A restore during an incident would leave an orphan nobody can attribute.
  copy_tags_to_snapshot = true
  skip_final_snapshot   = var.skip_final_snapshot
  final_snapshot_identifier = (
    var.skip_final_snapshot ? null : "${var.name}-search-final-${formatdate("YYYYMMDDhhmmss", timestamp())}"
  )
  deletion_protection = var.deletion_protection

  auto_minor_version_upgrade = true
  apply_immediately          = var.apply_immediately

  # IAM authentication.
  #
  # The master password still exists -- RDS requires one -- but with this on, the
  # application connects using a short-lived token derived from its IRSA role
  # instead of a long-lived secret. The credential that would leak in a heap dump
  # or a log line expires in fifteen minutes.
  iam_database_authentication_enabled = var.iam_authentication

  # Enhanced monitoring reads from the host, not from inside the engine. The
  # difference matters exactly when it is needed: when the instance is starved of
  # CPU or IOPS, the in-engine metrics that CloudWatch normally reports are
  # themselves delayed, so the graph flatlines at the moment of interest.
  monitoring_interval = var.monitoring_interval
  monitoring_role_arn = var.monitoring_interval > 0 ? aws_iam_role.monitoring[0].arn : null

  parameter_group_name = aws_db_parameter_group.this.name

  # Postgres logs to CloudWatch, because an instance that has been replaced takes
  # its local logs with it -- and the interesting logs are usually the ones from
  # just before the replacement.
  enabled_cloudwatch_logs_exports = var.log_exports

  performance_insights_enabled = var.performance_insights
  # Performance Insights stores query text, which for this workload includes
  # search terms typed by users. That is user data and belongs under the same key
  # as the rest of it.
  performance_insights_kms_key_id = var.performance_insights ? var.kms_key_arn : null

  tags = merge(var.tags, { Name = "${var.name}-search" })

  lifecycle {
    ignore_changes = [
      # formatdate(timestamp()) changes on every plan, so without this the
      # instance shows a permanent diff and eventually somebody applies it.
      final_snapshot_identifier,
    ]
  }
}
