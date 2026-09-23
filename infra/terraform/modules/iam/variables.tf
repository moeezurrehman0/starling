variable "name" {
  description = "Role name prefix. Must match what deploy/envs/<env>/*.yaml annotates, or the pods get no credentials and the SDK falls back to the node role -- silently."
  type        = string
}

variable "namespace" {
  description = "Kubernetes namespace of the service accounts. Part of the trust policy's sub condition, so a namespace rename breaks assumption."
  type        = string
}

variable "region" {
  description = "Region the tables live in, for ARN construction."
  type        = string
}

variable "oidc_provider_arn" {
  description = "From the eks module. Null means no IRSA is possible; the sandbox root simply does not instantiate this module."
  type        = string
}

variable "oidc_provider_url" {
  description = "The cluster's OIDC issuer URL, with scheme."
  type        = string
}

variable "table_prefix" {
  description = "Prefix applied to table names by the dynamodb module."
  type        = string
  default     = ""
}

variable "media_bucket_arn" {
  description = "S3 media bucket ARN."
  type        = string
  default     = null
}

variable "services" {
  description = <<-EOT
    Per-service grants, keyed by the Kubernetes service account name -- which is
    also the workload name in the chart.

    tables_read / tables_write / table_streams are bare table names; the prefix
    and the ARN are applied here. media_bucket_access is "none", "ro" or "rw".
  EOT
  type = map(object({
    tables_read         = optional(list(string), [])
    tables_write        = optional(list(string), [])
    table_streams       = optional(list(string), [])
    media_bucket_access = optional(string, "none")
  }))

  # The default is the real mapping, derived by reading what each service opens.
  # It lives here rather than in each env root so that the two tiers cannot drift:
  # a grant that exists in sandbox but not prod is the kind of difference that is
  # only discovered by an outage.
  default = {
    # No data access. The gateway routes, authenticates against user-service and
    # terminates nothing of its own. The role exists so every service account is
    # annotated identically; it grants nothing.
    gateway = {}

    user-service = {
      tables_read         = ["users", "handles", "credentials", "follows"]
      tables_write        = ["users", "handles", "credentials", "follows"]
      media_bucket_access = "rw" # avatars
    }

    tweet-service = {
      tables_read  = ["tweets", "likes", "idempotency"]
      tables_write = ["tweets", "likes", "idempotency"]
      # Writes its own checkpoint but never reads another consumer's.
      media_bucket_access = "rw"
    }

    timeline-service = {
      # Read-only, and only the fanned-out timeline plus the tweets it points at.
      # A timeline entry is a tweet id; hydrating it is the one cross-table read.
      tables_read = ["timelines", "tweets"]
    }

    fanout-worker = {
      tables_read   = ["follows", "tweets", "users"]
      tables_write  = ["timelines", "stream_checkpoints"]
      table_streams = ["tweets"]
    }

    tweet-indexer = {
      tables_read   = ["tweets"]
      tables_write  = ["stream_checkpoints"]
      table_streams = ["tweets"]
    }

    # web is a static frontend. It gets no role at all, which is why it is absent
    # rather than present-and-empty.
  }

  validation {
    condition     = alltrue([for s in var.services : contains(["none", "ro", "rw"], s.media_bucket_access)])
    error_message = "media_bucket_access must be one of none, ro, rw."
  }
}

variable "tags" {
  description = "Tags applied to every role and policy."
  type        = map(string)
  default     = {}
}
