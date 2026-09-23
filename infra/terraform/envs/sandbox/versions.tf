terraform {
  required_version = ">= 1.9.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.70"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.6"
    }
    tls = {
      source  = "hashicorp/tls"
      version = "~> 4.0"
    }
  }

  # Local state, deliberately.
  #
  # The remote-state answer is an S3 bucket with a DynamoDB lock table, and it is
  # the right answer everywhere except here: the bucket would live in the same
  # 180-minute account as the infrastructure it describes, so it dies with its own
  # backend. Local state in a gitignored directory is the honest version of "this
  # account is disposable" -- and it is gap-register row 12, not a shortcut being
  # quietly taken.
  #
  # envs/prod declares the S3 backend properly. The diff between these two files
  # is part of the deliverable.
}

provider "aws" {
  region = var.region

  default_tags {
    tags = local.tags
  }
}
