# Unit tests for the registry module.

mock_provider "aws" {}

variables {
  name     = "starling"
  services = ["gateway", "user-service", "tweet-service", "timeline-service", "fanout-worker", "web"]
}

run "one_repository_per_service_with_immutable_tags" {
  command = plan

  assert {
    condition     = length(aws_ecr_repository.this) == 6
    error_message = "expected one repository per service"
  }

  # Mutable tags make a deployed digest unknowable after the fact. "which commit
  # is in prod" stops having an answer, and a rollback to a tag can land on
  # different bytes than the ones that tag originally named.
  assert {
    condition = alltrue([
      for r in aws_ecr_repository.this : r.image_tag_mutability == "IMMUTABLE"
    ])
    error_message = "a repository allows mutable tags; a tag must name one digest forever or rollback is not a defined operation"
  }

  assert {
    condition = alltrue([
      for r in aws_ecr_repository.this : r.image_scanning_configuration[0].scan_on_push
    ])
    error_message = "scan-on-push is off somewhere; the CI Trivy gate only sees what CI built, not what was pushed later"
  }
}

run "sandbox_defaults_do_not_block_teardown" {
  command = plan

  # A non-empty repository blocks destroy. In a 180-minute session that means the
  # teardown half-finishes and the next session inherits it.
  assert {
    condition = alltrue([
      for r in aws_ecr_repository.this : r.force_delete == false
    ])
    error_message = "force_delete defaults on; the default must be the safe one and the sandbox root opts in explicitly"
  }
}

run "keeping_fewer_than_two_images_makes_rollback_impossible" {
  command = plan

  variables {
    keep_last = 1
  }

  # Not a style preference. With one image retained, the image you would roll
  # back to has already been expired by the lifecycle policy at the moment you
  # need it.
  expect_failures = [var.keep_last]
}
