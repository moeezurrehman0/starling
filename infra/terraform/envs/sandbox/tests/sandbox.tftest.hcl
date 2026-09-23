# Tests for the sandbox root.
#
# This is the tier that actually gets applied, into a disposable account, against
# a 180-minute clock. The failure mode that matters here is not insecurity -- the
# account evaporates -- it is an apply that dies halfway and burns the session.
# So these assert on staging and destroyability rather than on posture.
#
# The other thing they assert is that the sandbox is *honest*: it does several
# things production must never do, and each one is supposed to be visible in an
# output rather than buried in a variable default.

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

# The two adopted role ARNs. Tier S does not create IAM roles -- the playground's
# IAM is largely read-only and a role create is a likely denial mid-apply -- so
# the roles are passed in. They have no defaults precisely so that a session
# cannot start by silently adopting the wrong ones.
variables {
  cluster_role_arn = "arn:aws:iam::111111111111:role/playground-eks-cluster"
  node_role_arn    = "arn:aws:iam::111111111111:role/playground-eks-node"
}

run "the_default_apply_is_the_cheap_one" {
  command = plan

  # EKS takes most of a session to create and delete. It is opt-in so that the
  # data-plane half of the stack can be applied, exercised and destroyed inside
  # the budget, and so that a mistyped `make sandbox-up` does not spend the
  # session provisioning a cluster nobody asked for.
  assert {
    condition     = length(module.eks) == 0
    error_message = "EKS is created by default; a session should not be able to spend itself on a cluster the operator did not ask for"
  }

  assert {
    condition     = length(module.search_db) == 0
    error_message = "the search database is created by default"
  }

  assert {
    condition     = length(module.irsa) == 0
    error_message = "IRSA roles are created by default, which the playground's read-only IAM will likely refuse partway through the apply"
  }
}

run "irsa_cannot_be_enabled_without_the_cluster_it_trusts" {
  command = plan

  variables {
    enable_irsa = true
    enable_eks  = false
  }

  # An IRSA role needs an OIDC provider that only exists with the cluster.
  # Without the guard this is not an error, it is six roles with a trust policy
  # pointing at nothing -- which apply cleanly and grant nothing, and whose pods
  # then quietly fall back to the node role.
  assert {
    condition     = length(module.irsa) == 0
    error_message = "IRSA was created without EKS; the roles would trust an OIDC provider that does not exist and every pod would silently use the node role instead"
  }
}

run "the_data_plane_is_always_created_because_it_is_cheap_and_fast" {
  command = plan

  # DynamoDB on-demand, an S3 bucket and ECR repositories cost nothing at rest
  # and create in seconds. There is no reason to stage them.
  assert {
    condition     = length(module.dynamodb.table_names) == 9
    error_message = "the sandbox does not create all 9 tables; it must read the same schema file as production or local and deployed drift"
  }

  assert {
    condition     = length(module.registry.repository_urls) == 6
    error_message = "the sandbox does not create a repository per service"
  }
}

run "the_sandbox_admits_what_it_is" {
  command = plan

  # The sandbox adopts the default VPC, whose subnets are public, because the
  # playground's IAM will not reliably allow creating one. That is a real
  # difference from production and it is surfaced as an output rather than left
  # implicit -- the gap register's whole method is that deviations are stated,
  # not discovered.
  assert {
    condition     = output.nodes_are_public == true
    error_message = "the sandbox reports its nodes as private; it adopts the default VPC, whose subnets are public, and saying otherwise makes the gap register a lie"
  }
}
