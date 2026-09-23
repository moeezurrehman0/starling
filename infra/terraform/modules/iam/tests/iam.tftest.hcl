# Unit tests for the iam module. mock_provider, so nothing is created and no
# credentials are needed.
#
# A deliberate limit worth writing down: aws_iam_policy_document renders its JSON
# in the provider, so under a mock the `.json` attribute is a fake string. These
# tests therefore cannot assert on policy *content* -- that the trust policy pins
# both :sub and :aud, that reads include /index/*, that ListStreams is scoped to
# "*". Those live in scripts/tf-validate.sh as source assertions instead. What is
# testable here is the role topology: which principals exist, which of them can
# reach data at all, and whether the names line up with the workloads that will
# assume them. That is the part a refactor breaks.

mock_provider "aws" {
  # Without this the mocked `json` attribute is a random string and
  # aws_iam_policy rejects it with "not a JSON object" before any assertion
  # runs -- which is itself the proof that the policy body is provider-rendered
  # and therefore not assertable here.
  mock_data "aws_iam_policy_document" {
    defaults = {
      json = "{\"Version\":\"2012-10-17\",\"Statement\":[]}"
    }
  }
}

variables {
  name              = "twitter-clone"
  namespace         = "twitter-clone"
  region            = "eu-central-1"
  oidc_provider_arn = "arn:aws:iam::111111111111:oidc-provider/oidc.eks.eu-central-1.amazonaws.com/id/EXAMPLED539D4633E53DE1B716D3041E"
  oidc_provider_url = "https://oidc.eks.eu-central-1.amazonaws.com/id/EXAMPLED539D4633E53DE1B716D3041E"
  media_bucket_arn  = "arn:aws:s3:::twitter-clone-media"
}

run "one_role_per_service_and_web_has_none" {
  command = plan

  assert {
    condition = toset(keys(aws_iam_role.this)) == toset([
      "gateway", "user-service", "tweet-service",
      "timeline-service", "fanout-worker", "tweet-indexer",
    ])
    error_message = "the set of IRSA roles changed. If a service was added, its grants must be added to the module default rather than to one env root, or the tiers drift and only an outage finds it."
  }

  # web is a static frontend with no AWS call in it. Absent rather than
  # present-and-empty: an empty role invites someone to fill it in.
  assert {
    condition     = !contains(keys(aws_iam_role.this), "web")
    error_message = "web has an IRSA role; it is a static frontend and should not be able to assume anything"
  }
}

run "the_gateway_can_assume_a_role_but_reach_no_data" {
  command = plan

  # The gateway routes and authenticates. It owns no table. It still gets a role
  # so that every service account is annotated identically -- the alternative is
  # a special case in the chart that someone eventually copies.
  assert {
    condition     = contains(keys(aws_iam_role.this), "gateway")
    error_message = "the gateway has no role; every service account is annotated the same way and a missing role makes that a special case"
  }

  assert {
    condition     = !contains(keys(aws_iam_policy.access), "gateway")
    error_message = "the gateway has an access policy attached; it terminates no data path and should reach nothing"
  }
}

run "every_service_that_touches_data_has_exactly_one_policy_attached" {
  command = plan

  assert {
    condition     = length(aws_iam_policy.access) == length(aws_iam_role_policy_attachment.access)
    error_message = "a policy exists that is attached to no role, or a role is attached to a policy that does not exist -- either way some service silently falls back to no permissions"
  }

  assert {
    condition = toset(keys(aws_iam_policy.access)) == toset([
      "user-service", "tweet-service", "timeline-service",
      "fanout-worker", "tweet-indexer",
    ])
    error_message = "the set of services with data access changed"
  }
}

run "role_names_are_prefixed_so_two_clusters_can_coexist" {
  command = plan

  # IAM is global to the account. Two clusters in one account without a prefix
  # means the second apply silently adopts the first's roles.
  assert {
    condition = alltrue([
      for k, r in aws_iam_role.this : startswith(r.name, "twitter-clone-")
    ])
    error_message = "a role name is missing the cluster prefix; IAM is account-global and an unprefixed name is adopted, not rejected"
  }

  assert {
    condition     = aws_iam_role.this["fanout-worker"].max_session_duration == 3600
    error_message = "session duration changed; an hour is short enough that a leaked credential expires within a shift"
  }
}

run "only_the_stream_consumers_are_granted_streams" {
  command = plan

  # fan-out and the indexer read the tweets stream. Nothing else should: a stream
  # grant is a licence to replay every write to the table.
  assert {
    condition = toset([
      for k, s in var.services : k if length(s.table_streams) > 0
    ]) == toset(["fanout-worker", "tweet-indexer"])
    error_message = "a service other than fanout-worker/tweet-indexer was granted DynamoDB Streams access"
  }
}

run "timeline_service_stays_read_only" {
  command = plan

  # The read path must not be able to write timelines. If it can, a bug in
  # hydration becomes a corrupted timeline rather than a 500.
  assert {
    condition     = length(var.services["timeline-service"].tables_write) == 0
    error_message = "timeline-service was granted writes; the read path must not be able to mutate timelines"
  }

  assert {
    condition     = var.services["timeline-service"].media_bucket_access == "none"
    error_message = "timeline-service was granted media bucket access it does not use"
  }
}
