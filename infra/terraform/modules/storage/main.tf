# S3 — the media bucket.

resource "random_id" "suffix" {
  # Bucket names are globally unique across every AWS account on earth, and the
  # playground documentation says so explicitly because it is the most common way
  # a sandbox apply fails. A random suffix makes the name unguessable and makes
  # two concurrent sessions possible.
  byte_length = 4
}

resource "aws_s3_bucket" "media" {
  # checkov:skip=CKV_AWS_144:Cross-region replication is a disaster-recovery
  #   decision, not a security control, and it doubles storage cost plus
  #   requires a second region this project deliberately does not have. The DR
  #   posture is stated in docs/16-gap-register.md rather than implied by a
  #   replication rule nobody would fail over to.
  # checkov:skip=CKV2_AWS_62:Event notifications would fire into a consumer that
  #   does not exist. Media is written by the service and read through
  #   CloudFront; nothing in this design reacts to an object landing. Wiring an
  #   unused notification to satisfy a scanner is how dead infrastructure starts.
  bucket = "${var.name}-media-${random_id.suffix.hex}"

  # Refuse to delete a bucket with objects in it, unless told otherwise. In Tier S
  # this is true, because `make sandbox-down` must actually complete; everywhere
  # else it is the difference between a mistaken destroy and a lost dataset.
  force_destroy = var.force_destroy

  tags = merge(var.tags, { Name = "${var.name}-media" })
}

# Access logging.
#
# Not for compliance: the media bucket is the one place a leaked presigned URL or
# a misconfigured CORS origin shows up as real traffic, and without a log there
# is no way to answer "was this object ever fetched, and by whom" after the fact.
# The log bucket cannot log to itself, which is why it carries its own skip.
resource "aws_s3_bucket" "logs" {
  # checkov:skip=CKV_AWS_145:SSE-S3, not KMS, and deliberately so. The S3 log
  #   delivery service writes as a service principal; pointing the target bucket
  #   at a customer-managed key whose policy does not grant that principal makes
  #   log delivery stop with no error and no logs. A bucket that looks configured
  #   and silently records nothing is worse than one that is plainly SSE-S3.
  # checkov:skip=CKV_AWS_18:This is the access-log target. Logging a log bucket
  #   into itself is a recursion AWS rejects, and into a third bucket is a chain
  #   with the same problem one level down.
  # checkov:skip=CKV_AWS_144:See the media bucket. Access logs are reconstructible
  #   and not worth cross-region replication.
  # checkov:skip=CKV2_AWS_62:Nothing consumes log-object-created events.
  bucket        = "${var.name}-logs-${random_id.suffix.hex}"
  force_destroy = var.force_destroy

  tags = merge(var.tags, { Name = "${var.name}-logs" })
}

resource "aws_s3_bucket_public_access_block" "logs" {
  bucket = aws_s3_bucket.logs.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_server_side_encryption_configuration" "logs" {
  bucket = aws_s3_bucket.logs.id

  rule {
    apply_server_side_encryption_by_default {
      # Deliberately SSE-S3 and not the customer-managed key. S3 log delivery
      # writes as a service principal, and pointing it at a KMS key whose policy
      # does not grant that principal makes logging fail silently -- no error,
      # simply no logs, which is worse than no logging at all because the bucket
      # looks configured.
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_versioning" "logs" {
  bucket = aws_s3_bucket.logs.id
  versioning_configuration {
    status = var.versioning ? "Enabled" : "Disabled"
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "logs" {
  bucket = aws_s3_bucket.logs.id

  rule {
    id     = "abort-incomplete-multipart"
    status = "Enabled"
    filter {}
    abort_incomplete_multipart_upload {
      days_after_initiation = 7
    }
  }

  rule {
    id     = "expire-logs"
    status = "Enabled"

    filter {}

    expiration {
      days = var.log_retention_days
    }
  }

  depends_on = [aws_s3_bucket_versioning.logs]
}

resource "aws_s3_bucket_logging" "media" {
  bucket        = aws_s3_bucket.media.id
  target_bucket = aws_s3_bucket.logs.id
  target_prefix = "media/"
}

# All four settings, explicitly. The account-level default has changed twice and
# is not something to depend on; more importantly, a bucket that is public
# *because a default changed* is indistinguishable from one that was configured
# that way on purpose.
resource "aws_s3_bucket_public_access_block" "media" {
  bucket = aws_s3_bucket.media.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_server_side_encryption_configuration" "media" {
  bucket = aws_s3_bucket.media.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm     = var.kms_key_arn == null ? "AES256" : "aws:kms"
      kms_master_key_id = var.kms_key_arn
    }
    # Without this, every GET and PUT is a separate KMS call: billed per request
    # and subject to a per-account KMS rate limit that a media-heavy workload will
    # find. It reuses one data key per bucket for a short window and cuts KMS
    # traffic by orders of magnitude.
    bucket_key_enabled = var.kms_key_arn != null
  }
}

resource "aws_s3_bucket_versioning" "media" {
  bucket = aws_s3_bucket.media.id
  versioning_configuration {
    status = var.versioning ? "Enabled" : "Disabled"
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "media" {
  # checkov:skip=CKV_AWS_300:The abort-incomplete-multipart rule is the first rule
  #   below. Checkov evaluates the dynamic "rule" block alongside the static one
  #   and reports the config as a whole when any rule lacks the setting, which is
  #   not what the control means -- one abort rule applies to the bucket.
  bucket = aws_s3_bucket.media.id

  # A multipart upload that fails leaves its parts behind, billable, invisible in
  # the console's object list, and forever. This is the single cheapest lifecycle
  # rule there is and the one most often missing.
  rule {
    id     = "abort-incomplete-multipart"
    status = "Enabled"
    filter {}
    abort_incomplete_multipart_upload {
      days_after_initiation = 7
    }
  }

  dynamic "rule" {
    for_each = var.versioning ? [1] : []
    content {
      id     = "expire-noncurrent"
      status = "Enabled"
      filter {}
      noncurrent_version_expiration {
        noncurrent_days = 30
      }
    }
  }

  depends_on = [aws_s3_bucket_versioning.media]
}

# CORS, because the browser uploads directly to S3 with a presigned URL. Without
# it the upload fails in the browser only -- curl against the same URL succeeds,
# which sends everyone looking at the signature rather than at the bucket.
resource "aws_s3_bucket_cors_configuration" "media" {
  bucket = aws_s3_bucket.media.id

  cors_rule {
    allowed_headers = ["*"]
    allowed_methods = ["GET", "PUT", "HEAD"]
    allowed_origins = var.cors_origins
    expose_headers  = ["ETag"]
    max_age_seconds = 3000
  }
}
