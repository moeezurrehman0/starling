# ECR — one repository per service.

resource "aws_ecr_repository" "this" {
  for_each = toset(var.services)

  name = "${var.name}/${each.key}"

  # MUTABLE would let `sha-abc1234` be overwritten, which makes a rollback a
  # guess: the tag a pod is running no longer identifies the bytes it is running.
  # The chart already refuses `latest` for the same reason; this enforces it at
  # the registry, where it cannot be bypassed by a values file.
  image_tag_mutability = "IMMUTABLE"

  image_scanning_configuration {
    # Scan on push, so a vulnerable image is flagged when it is built rather than
    # when someone remembers to look. CI already runs Trivy; this catches the
    # images CI did not build.
    scan_on_push = true
  }

  encryption_configuration {
    encryption_type = var.kms_key_arn == null ? "AES256" : "KMS"
    kms_key         = var.kms_key_arn
  }

  force_delete = var.force_delete

  tags = merge(var.tags, { Name = "${var.name}/${each.key}" })
}

resource "aws_ecr_lifecycle_policy" "this" {
  for_each   = aws_ecr_repository.this
  repository = each.value.name

  # Two rules, and the order matters: ECR evaluates by rule priority and an image
  # matched by an earlier rule is not considered by a later one.
  #
  # Untagged images first. They are almost always orphaned layers from a
  # multi-arch build or an overwritten manifest, and they are pure cost.
  policy = jsonencode({
    rules = [
      {
        rulePriority = 1
        description  = "Expire untagged images after a day"
        selection = {
          tagStatus   = "untagged"
          countType   = "sinceImagePushed"
          countUnit   = "days"
          countNumber = 1
        }
        action = { type = "expire" }
      },
      {
        rulePriority = 2
        description  = "Keep the last ${var.keep_last} release images"
        selection = {
          tagStatus     = "tagged"
          tagPrefixList = ["sha-"]
          countType     = "imageCountMoreThan"
          countNumber   = var.keep_last
        }
        action = { type = "expire" }
      },
    ]
  })
}
