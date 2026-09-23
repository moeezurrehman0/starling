# Tests for the production root. Mocked providers, so this asserts the
# composition -- what the modules are wired to do together -- rather than any one
# module's behaviour, which the module's own tests cover.
#
# This root is never applied. That is exactly why it needs tests: nothing else
# will ever tell us it is wrong. Tier S gets corrected by reality inside 180
# minutes; Tier P only ever gets corrected by a reader.
#
# Scope limit worth stating: at root level a test can see module *outputs* and
# root resources, not the resources inside a module. So "the API endpoint is
# private" and "the bucket is not force-destroyable" are not assertable here --
# they are source assertions in scripts/tf-validate.sh. What is assertable here
# is everything that crosses a module boundary, which is precisely the part no
# single module's tests can check.

mock_provider "aws" {
  mock_data "aws_iam_policy_document" {
    defaults = {
      json = "{\"Version\":\"2012-10-17\",\"Statement\":[]}"
    }
  }
  mock_data "aws_partition" {
    defaults = {
      partition = "aws"
    }
  }
}

mock_provider "tls" {}
mock_provider "random" {}

run "production_reads_the_same_schema_file_as_everything_else" {
  command = plan

  # tools/dynamodb-tables.json is read by this module, by the Compose bootstrap
  # and by the in-cluster bootstrap. The failure it prevents is a GSI that exists
  # locally and not in AWS, which surfaces as a ValidationException after deploy,
  # on one code path, under load.
  assert {
    condition     = length(module.dynamodb.table_names) == 9
    error_message = "the production root does not create all 9 tables from the shared schema file"
  }
}

run "no_node_is_reachable_from_the_internet" {
  command = plan

  # The sandbox adopts the default VPC, whose subnets are public, and it says so.
  # Production must not. This is the assertion that would catch a copy-paste from
  # one root to the other -- the most likely way the two tiers converge on the
  # wrong one.
  assert {
    condition     = module.network.nodes_are_public == false
    error_message = "production nodes are on public subnets; the sandbox's default-VPC shortcut has been copied into the tier that cannot afford it"
  }
}

run "capacity_survives_losing_an_availability_zone" {
  command = plan

  assert {
    condition     = length(module.network.node_subnet_ids) >= 3
    error_message = "fewer than three node subnets; an AZ failure takes a third of capacity with nowhere for it to go"
  }
}

run "every_workload_that_needs_a_role_has_one_and_web_does_not" {
  command = plan

  # This crosses three boundaries: the IRSA module's service map, the chart's
  # workload names, and the ECR repository list. A rename in any one of them
  # produces pods that fall back to the node instance role -- working, with more
  # access than intended, silently.
  assert {
    condition     = length(module.irsa.role_arns) == 6
    error_message = "the number of IRSA roles in production changed"
  }

  assert {
    condition     = !contains(keys(module.irsa.role_arns), "web")
    error_message = "the static frontend has an AWS role in production"
  }
}

run "every_service_that_ships_an_image_has_somewhere_to_push_it" {
  command = plan

  # A missing repository is not a plan error. It is a push failure in the release
  # job, after the build, after the tests, at the point where the pipeline has
  # already reported most of itself green.
  assert {
    condition = alltrue([
      for s in var.services : contains(keys(module.registry.repository_urls), s)
    ])
    error_message = "a service in var.services has no ECR repository; the failure lands in the release job after everything else is green"
  }
}
