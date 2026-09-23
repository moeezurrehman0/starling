output "bucket_name" {
  description = "Resolved bucket name, including the random suffix."
  value       = aws_s3_bucket.media.id
}

output "bucket_arn" {
  description = "Bucket ARN, for IAM policy construction."
  value       = aws_s3_bucket.media.arn
}
