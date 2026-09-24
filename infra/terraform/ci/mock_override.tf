# SPDX-License-Identifier: MIT
#
# Copied into a Terraform root by `scripts/tf-plan-mock.sh` to make
# `terraform plan` succeed with no AWS account.
#
# Terraform merges any file whose name ends in `_override.tf` over the base
# configuration, so this replaces the root's own `provider "aws"` block rather
# than colliding with it. It is deliberately NOT stored in either root: a stray
# copy there would silently redirect a real apply at a mock endpoint.
#
# Two separate problems have to be solved to plan without credentials:
#
#   1. The provider validates credentials at configure time. The four `skip_*`
#      arguments exist for exactly this and cost nothing.
#   2. `data "aws_caller_identity"` in the iam module is a real API call that no
#      flag can skip, because the account id genuinely appears in the rendered
#      IAM policies. That one needs something to answer, which is why the
#      endpoints below point at a LocalStack container.
#
# The result is a *real* plan of the production root -- 95 resources, real
# module wiring, real rendered policy documents -- produced on a runner with no
# AWS access at all. That is what lets the risk commenter run on every pull
# request instead of only when someone has a session open.

provider "aws" {
  region = var.region

  access_key = "mock"
  secret_key = "mock"

  skip_credentials_validation = true
  skip_requesting_account_id  = true
  skip_metadata_api_check     = true
  skip_region_validation      = true

  endpoints {
    sts = "http://localhost:4566"
    iam = "http://localhost:4566"
    ec2 = "http://localhost:4566"
  }
}
