# Unit tests for the storage module (the media bucket).

mock_provider "aws" {}

variables {
  name = "starling"
}

run "the_bucket_is_private_on_all_four_switches" {
  command = plan

  # All four, because they are not redundant: the ACL switches and the policy
  # switches cover different ways a bucket becomes public, and three out of four
  # is a public bucket waiting for the fourth path to be used.
  assert {
    condition = (
      aws_s3_bucket_public_access_block.media.block_public_acls &&
      aws_s3_bucket_public_access_block.media.block_public_policy &&
      aws_s3_bucket_public_access_block.media.ignore_public_acls &&
      aws_s3_bucket_public_access_block.media.restrict_public_buckets
    )
    error_message = "the media bucket is not fully blocked from public access; these four switches cover four different routes and any one left open is the route that gets used"
  }
}

run "defaults_are_encrypted_versioned_and_not_destroyable" {
  command = plan

  assert {
    condition     = aws_s3_bucket.media.force_destroy == false
    error_message = "force_destroy defaults on; the sandbox root opts in, production must not be able to"
  }

  assert {
    condition     = aws_s3_bucket_versioning.media.versioning_configuration[0].status == "Enabled"
    error_message = "versioning defaults off; without it an overwrite is unrecoverable"
  }

  assert {
    condition     = one([for r in aws_s3_bucket_server_side_encryption_configuration.media.rule : one(r.apply_server_side_encryption_by_default).sse_algorithm]) == "AES256"
    error_message = "with no KMS key the bucket should still be encrypted with the S3-managed key"
  }

  # Failed multipart uploads leave billable parts that never appear in the
  # object list. This rule is the cheapest one there is and the most often absent.
  assert {
    condition = anytrue([
      for r in aws_s3_bucket_lifecycle_configuration.media.rule :
      r.id == "abort-incomplete-multipart" && r.status == "Enabled"
    ])
    error_message = "no enabled abort-incomplete-multipart rule; orphaned upload parts bill forever and are invisible in the console"
  }
}

run "a_customer_managed_key_also_turns_on_the_bucket_key" {
  command = plan

  variables {
    kms_key_arn = "arn:aws:kms:eu-central-1:111111111111:key/00000000-0000-0000-0000-000000000000"
  }

  assert {
    condition     = one([for r in aws_s3_bucket_server_side_encryption_configuration.media.rule : one(r.apply_server_side_encryption_by_default).sse_algorithm]) == "aws:kms"
    error_message = "a KMS key was supplied but the bucket still encrypts with AES256"
  }

  # Without the bucket key every GET and PUT is a billed KMS call against a
  # per-account rate limit a media workload will find. Supplying a key and
  # forgetting this is a working configuration that gets expensive and then
  # starts throttling.
  assert {
    condition     = one([for r in aws_s3_bucket_server_side_encryption_configuration.media.rule : r.bucket_key_enabled])
    error_message = "KMS encryption without bucket_key_enabled: every object request becomes a KMS request"
  }
}
