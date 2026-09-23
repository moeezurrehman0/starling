# Unit tests for the eks module.

mock_provider "aws" {
  mock_data "aws_iam_policy_document" {
    defaults = {
      json = "{\"Version\":\"2012-10-17\",\"Statement\":[]}"
    }
  }

  # A mocked partition is a random string, and every managed-policy ARN built
  # from it then fails the provider's own ARN validation. Pinning it is also
  # closer to the truth: the module only ever runs in a commercial partition.
  mock_data "aws_partition" {
    defaults = {
      partition = "aws"
    }
  }
}

mock_provider "tls" {}

variables {
  name            = "twitter-clone"
  subnet_ids      = ["subnet-aaaaaaaa", "subnet-bbbbbbbb"]
  node_subnet_ids = ["subnet-aaaaaaaa", "subnet-bbbbbbbb"]
}

run "access_is_granted_through_the_api_not_the_aws_auth_configmap" {
  command = plan

  # The ConfigMap has no schema and no validation: a malformed edit is accepted
  # and locks every principal out of the cluster, with no undo that does not
  # involve the creating identity. The API mode is a real AWS resource that can
  # be planned, reviewed and reverted.
  assert {
    condition     = aws_eks_cluster.this.access_config[0].authentication_mode == "API"
    error_message = "the cluster falls back to the aws-auth ConfigMap; a bad edit there locks everyone out and is not revertible through Terraform"
  }
}

run "the_control_plane_logs_everything_that_matters" {
  command = plan

  # audit and authenticator are the two that answer "who did this". They are off
  # by default in EKS and cannot be enabled retroactively for events that already
  # happened.
  assert {
    condition = alltrue([
      for t in ["api", "audit", "authenticator"] :
      contains(aws_eks_cluster.this.enabled_cluster_log_types, t)
    ])
    error_message = "a control-plane log type is disabled; audit and authenticator cannot be turned on after the fact for events you needed them for"
  }
}

run "the_node_group_ignores_autoscaler_managed_capacity" {
  command = plan

  # desired_size is owned by the autoscaler at runtime. Without the
  # ignore_changes in the module, every subsequent plan proposes reverting to the
  # committed number -- so an apply during an incident scales the cluster down.
  assert {
    condition     = aws_eks_node_group.this.scaling_config[0].min_size <= aws_eks_node_group.this.scaling_config[0].desired_size
    error_message = "min_size exceeds desired_size; the node group will not create"
  }

  assert {
    condition     = aws_eks_node_group.this.scaling_config[0].desired_size <= aws_eks_node_group.this.scaling_config[0].max_size
    error_message = "desired_size exceeds max_size; the node group will not create"
  }
}

run "sandbox_defaults_fit_the_playground" {
  command = plan

  # t3.medium and three nodes are the playground ceiling, not a sizing decision.
  # Gap register: production would not choose either.
  assert {
    condition     = contains(aws_eks_node_group.this.instance_types, "t3.medium")
    error_message = "the default instance type is not t3.medium, which is the playground's hard ceiling"
  }

  assert {
    condition     = aws_eks_node_group.this.scaling_config[0].max_size <= 3
    error_message = "the default node group can exceed 3 nodes, which the playground refuses -- the apply fails partway through a 180-minute session"
  }
}

run "a_private_endpoint_is_reachable_through_variables" {
  command = plan

  variables {
    endpoint_public_access = false
    public_access_cidrs    = []
  }

  assert {
    condition     = !aws_eks_cluster.this.vpc_config[0].endpoint_public_access
    error_message = "endpoint_public_access = false did not reach the cluster; this is the single most important difference between the two tiers"
  }

  assert {
    condition     = aws_eks_cluster.this.vpc_config[0].endpoint_private_access
    error_message = "private access is off while public access is also off -- the cluster would be unreachable by anything"
  }
}

run "an_openid_connect_provider_exists_or_irsa_is_impossible" {
  command = plan

  # Without this, every pod falls back to the node instance role. Nothing fails:
  # the pods keep working, with more permission than they should have, and the
  # per-service roles sit unused.
  assert {
    condition     = length(aws_iam_openid_connect_provider.this) == 1
    error_message = "no OIDC provider; IRSA cannot work and every pod silently uses the node role instead"
  }
}
