# Unit tests for the dynamodb module. No credentials, no network: mock_provider
# means the AWS provider is never configured and nothing is ever created.
#
# What these are for is narrow and worth stating. `terraform validate` type-checks;
# it has no opinion about whether the thing you built is the thing you meant
# (gap register #15). `scripts/tf-validate.sh` covers that with grep-level
# assertions over source. These tests cover the third layer: behaviour that only
# exists after evaluation -- the jsondecode, the for_each expansion, the
# preconditions -- which neither of the other two can see.

mock_provider "aws" {}

variables {
  definitions_file = "../../../../tools/dynamodb-tables.json"
}

run "every_table_in_the_schema_file_becomes_a_table" {
  command = plan

  # The count is deliberately hard-coded rather than derived from the same file
  # the module reads. A test that recomputes its expectation from its input
  # passes no matter what the input says.
  assert {
    condition     = length(aws_dynamodb_table.this) == 9
    error_message = "expected 9 tables from tools/dynamodb-tables.json, got ${length(aws_dynamodb_table.this)} -- if a table was added or removed, this number and tools/localstack/create-tables.py both need to know"
  }

  assert {
    condition = alltrue([
      for t in aws_dynamodb_table.this : t.billing_mode == "PAY_PER_REQUEST"
    ])
    error_message = "a table is on provisioned capacity; celebrity fan-out arrives in seconds and capacity autoscaling reacts in minutes"
  }
}

run "the_tweets_table_streams_or_fan_out_consumes_nothing" {
  command = plan

  # Gap register #18: the worker discovers this stream once at boot and, when
  # discovery fails, logs WARN and runs forever consuming nothing. Every timeline
  # in the product depends on this one attribute being true.
  assert {
    condition     = aws_dynamodb_table.this["tweets"].stream_enabled
    error_message = "the tweets table has no stream; fan-out has nothing to subscribe to and every home timeline stays empty with no error anywhere"
  }

  assert {
    condition     = aws_dynamodb_table.this["tweets"].stream_view_type == "NEW_IMAGE"
    error_message = "the tweets stream should carry NEW_IMAGE: fan-out only ever needs the row that was just written, and OLD_IMAGE doubles the stream payload for nothing"
  }

  # The inverse matters just as much. A stream on every table is a per-table cost
  # and a per-table consumer someone will eventually feel obliged to write.
  assert {
    condition     = !aws_dynamodb_table.this["follows"].stream_enabled
    error_message = "the follows table has a stream and nothing consumes it"
  }
}

run "sandbox_defaults_are_destroyable" {
  command = plan

  assert {
    condition = alltrue([
      for t in aws_dynamodb_table.this : t.deletion_protection_enabled == false
    ])
    error_message = "deletion protection defaults on; a 180-minute sandbox that cannot be torn down leaks into the next session"
  }

  assert {
    condition = alltrue([
      for t in aws_dynamodb_table.this : !t.point_in_time_recovery[0].enabled
    ])
    error_message = "PITR defaults on; it bills per GB for a restore window that cannot outlive the session"
  }
}

run "production_settings_are_reachable_through_variables" {
  command = plan

  variables {
    point_in_time_recovery = true
    deletion_protection    = true
    kms_key_arn            = "arn:aws:kms:eu-central-1:111111111111:key/00000000-0000-0000-0000-000000000000"
  }

  assert {
    condition = alltrue([
      for t in aws_dynamodb_table.this : t.point_in_time_recovery[0].enabled
    ])
    error_message = "point_in_time_recovery = true did not reach every table"
  }

  assert {
    condition = alltrue([
      for t in aws_dynamodb_table.this : t.server_side_encryption[0].enabled
    ])
    error_message = "a KMS key was supplied but SSE stayed off -- the data is encrypted with the AWS-owned key and the key you can revoke is doing nothing"
  }

  assert {
    condition = alltrue([
      for t in aws_dynamodb_table.this : t.deletion_protection_enabled
    ])
    error_message = "deletion_protection = true did not reach every table"
  }
}

run "an_attribute_used_by_no_key_or_index_is_rejected_at_plan_time" {
  command = plan

  variables {
    definitions_file = "tests/fixtures/unused-attribute.json"
  }

  # DynamoDB rejects this at create time with an error naming the attribute but
  # not the reason. In a 180-minute session, finding out at apply means a partial
  # apply. The module carries a lifecycle precondition; this proves it fires.
  expect_failures = [aws_dynamodb_table.this]
}
