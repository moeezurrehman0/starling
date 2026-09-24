terraform {
  required_version = ">= 1.9.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.66"
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

  # Remote state with native S3 locking.
  #
  # Commented out because this root is never initialised -- the bucket does not
  # exist and `terraform init` would fail, taking the CI validate job with it. The
  # declaration stays so the difference from envs/sandbox is visible in the file
  # rather than only in prose.
  #
  # use_lockfile (1.9+) replaces the DynamoDB lock table: S3 conditional writes do
  # the same job with one fewer resource and one fewer thing to forget.
  #
  # backend "s3" {
  #   bucket       = "starling-tfstate"
  #   key          = "prod/terraform.tfstate"
  #   region       = "eu-central-1"
  #   encrypt      = true
  #   kms_key_id   = "alias/tfstate"
  #   use_lockfile = true
  # }
}

provider "aws" {
  region = var.region

  default_tags {
    tags = local.tags
  }
}
