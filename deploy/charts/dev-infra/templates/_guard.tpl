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
