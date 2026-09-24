{{/*
The tier guard, the same shape as dev-infra's and for the same reason.
*/}}
{{- define "observability.guard" -}}
{{- if ne .Values.tier "dev" -}}
{{- fail "this observability stack is single-replica and unpersisted; Tier P uses AMP/AMG and must not install it" -}}
{{- end -}}
{{- end -}}

{{/*
Pod Security Standards, restricted profile.

Every component in this chart except promtail satisfies it. That is not luck -- it is the
reason promtail is in a separate namespace. Holding the monitoring stack to the same
admission profile as the application means an upstream image that needs an exemption is
discovered here, on a cluster that can be thrown away, rather than during the Tier S
session where there is no time to redesign anything.

Argument: the uid to run as.
*/}}
{{- define "observability.podSecurityContext" -}}
runAsNonRoot: true
runAsUser: {{ . }}
runAsGroup: {{ . }}
# The emptyDir is created root-owned. Without fsGroup the kubelet does not chgrp it, and
# Prometheus fails to open its own TSDB directory with a permission error on a path it was
# given rather than one it chose, which reads like a mount problem.
fsGroup: {{ . }}
seccompProfile:
  type: RuntimeDefault
{{- end -}}

{{- define "observability.containerSecurityContext" -}}
allowPrivilegeEscalation: false
readOnlyRootFilesystem: true
capabilities:
  drop: ["ALL"]
{{- end -}}
