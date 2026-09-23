# S3 — the media bucket.

resource "random_id" "suffix" {
  # Bucket names are globally unique across every AWS account on earth, and the
  # playground documentation says so explicitly because it is the most common way
  # a sandbox apply fails. A random suffix makes the name unguessable and makes
  # two concurrent sessions possible.
  byte_length = 4
}

resource "aws_s3_bucket" "media" {
  bucket = "${var.name}-media-${random_id.suffix.hex}"

  # Refuse to delete a bucket with objects in it, unless told otherwise. In Tier S
  # this is true, because `make sandbox-down` must actually complete; everywhere
  # else it is the difference between a mistaken destroy and a lost dataset.
  force_destroy = var.force_destroy

  tags = merge(var.tags, { Name = "${var.name}-media" })
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
