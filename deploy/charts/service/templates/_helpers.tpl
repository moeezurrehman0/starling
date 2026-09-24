{{/*
Helpers.

`required` is used rather than a default for anything whose wrong value would
deploy successfully. An image repository, a tag and a name all have that
property: a chart that renders with a plausible-looking placeholder produces a
running, wrong system, which is strictly worse than a template error.
*/}}

{{- define "service.name" -}}
{{- required "name is required — it is the object name, the Service DNS name and the selector" .Values.name -}}
{{- end -}}

{{- define "service.image" -}}
{{- $repo := required "image.repository is required" .Values.image.repository -}}
{{- $tag := required "image.tag is required — never rely on latest" .Values.image.tag -}}
{{- if eq (toString $tag) "latest" -}}
{{- fail "image.tag must not be 'latest': a mutable tag makes rollback a guess" -}}
{{- end -}}
{{- printf "%s:%s" $repo $tag -}}
{{- end -}}

{{- define "service.labels" -}}
app.kubernetes.io/name: {{ include "service.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Values.image.tag | quote }}
app.kubernetes.io/part-of: starling
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end -}}

{{- define "service.selectorLabels" -}}
app.kubernetes.io/name: {{ include "service.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{/*
Effective replica count. When the HPA owns the replica count, the Deployment must
not also declare one, or Helm and the HPA fight on every sync and ArgoCD reports
permanent drift.
*/}}
{{- define "service.replicas" -}}
{{- if .Values.autoscaling.enabled -}}
{{- .Values.autoscaling.minReplicas -}}
{{- else -}}
{{- .Values.replicaCount -}}
{{- end -}}
{{- end -}}

{{/*
Whether a PodDisruptionBudget should exist at all.

minAvailable: 1 against a single replica is not conservative, it is a deadlock:
the eviction API can never satisfy it, so `kubectl drain` hangs indefinitely and
a node rotation stalls. Rather than making every values file remember this, the
chart refuses to render a PDB unless there are at least two replicas to spare.
*/}}
{{- define "service.pdbWanted" -}}
{{- $replicas := int (include "service.replicas" .) -}}
{{- if and .Values.podDisruptionBudget.enabled (gt $replicas 1) -}}true{{- end -}}
{{- end -}}
