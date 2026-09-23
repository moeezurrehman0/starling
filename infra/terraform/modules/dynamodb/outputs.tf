output "table_names" {
  description = "Map of logical name to real table name."
  value       = { for k, t in aws_dynamodb_table.this : k => t.name }
}

output "table_arns" {
  description = "Map of logical name to ARN, for building least-privilege IAM policies."
  value       = { for k, t in aws_dynamodb_table.this : k => t.arn }
}

output "tweets_stream_arn" {
  description = <<-EOT
    Stream ARN for the tweets table.

    Exported for completeness and for IAM policy construction, but the services do
    NOT read it from configuration: StreamArns discovers it from the table at
    startup. A stream ARN contains a creation timestamp, so it changes every time
    the table is replaced -- a configured literal is correct exactly until the
    first teardown, and then silently points at a stream that no longer exists.
  EOT
  value       = aws_dynamodb_table.this["tweets"].stream_arn
}
