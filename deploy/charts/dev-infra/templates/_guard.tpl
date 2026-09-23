{{/*
The tier guard.

This lives in a define and is invoked from a real template, because content
placed loose in a .tpl file is never evaluated -- Helm only extracts the define
blocks. A guard written directly in _guard.tpl lints clean, renders nothing and
protects nothing, which is worse than having no guard at all.
*/}}
{{- define "dev-infra.guard" -}}
{{- if ne .Values.tier "dev" -}}
{{- fail "dev-infra is ephemeral and unreplicated; it must never be installed outside Tier L or Tier S" -}}
{{- end -}}
{{- end -}}

{{/*
Pod Security Standards, restricted profile.

The namespace enforces `restricted`, and these three images are the ones most
likely to fail it: they are upstream images written to run as root in Docker,
not workloads built for Kubernetes. Omitting the securityContext entirely -- as
this chart originally did -- produces a Deployment that is created successfully
and then never produces a Pod, because admission rejects the ReplicaSet rather
than the Deployment. `helm --wait` reports `context deadline exceeded` five
minutes later and names nothing.

The dev images are held to the same profile as the application ones on purpose.
A dependency that needs an exemption in Tier L is a dependency that would need
one in Tier S, and the cheapest time to discover that is here.
*/}}
{{- define "dev-infra.podSecurityContext" -}}
runAsNonRoot: true
runAsUser: {{ . }}
runAsGroup: {{ . }}
# fsGroup makes the kubelet chgrp the emptyDir to this gid on mount. Without it a
# non-root process cannot write to its own volume, and Postgres fails initdb with
# a permission error on a directory it just created.
fsGroup: {{ . }}
seccompProfile:
  type: RuntimeDefault
{{- end -}}

{{- define "dev-infra.containerSecurityContext" -}}
allowPrivilegeEscalation: false
capabilities:
  drop: ["ALL"]
{{- end -}}
