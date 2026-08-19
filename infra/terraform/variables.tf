variable "project_id" {
  description = "GCP project that hosts the instance. For ValueDocs: their own project, not EnrichMeAI's."
  type        = string
}

variable "region" {
  description = "Region for regional resources (Cloud NAT, the router)."
  type        = string
  default     = "europe-west2"
}

variable "zone" {
  description = "Zone for the instance and its data disk."
  type        = string
  default     = "europe-west2-a"
}

variable "name" {
  description = "Name prefix for every resource this module creates."
  type        = string
  default     = "cistern"
}

variable "domain" {
  description = <<-EOT
    The public hostname clients call, e.g. pod.valuedocs.co.in. Three things hang off it:
    the Google-managed certificate is issued for it, cistern.base-url is https://<domain>
    (it mints every identifier the pod hands out), and after apply an A record for it must
    point at `terraform output load_balancer_ip` — the certificate stays PROVISIONING
    until DNS resolves.
  EOT
  type        = string

  validation {
    condition     = can(regex("^([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,}$", var.domain))
    error_message = "domain must be a lower-case DNS hostname such as pod.example.org (no scheme, no path, no trailing dot)."
  }
}

variable "machine_type" {
  description = "Instance size. e2-small carries a single-firm pod comfortably; e2-medium if the JVM is short of memory."
  type        = string
  default     = "e2-small"
}

variable "data_disk_gb" {
  description = <<-EOT
    Size of the persistent disk holding the pod. A real filesystem is required: the storage
    backend writes tmp-then-ATOMIC_MOVE, so bucket-style storage (gcsfuse renames are
    copy-then-delete) would void its crash-safety guarantee. Growing it later is an in-place
    apply followed by `resize2fs` on the instance.
  EOT
  type        = number
  default     = 10
}

variable "image" {
  description = <<-EOT
    Container image to run, by pinned tag. Published on every v* tag by release.yml
    (linux/amd64 + linux/arm64). Change it and apply, then reset the instance
    (`terraform output upgrade_command`): the startup script re-pulls on boot.
  EOT
  type        = string
  default     = "ghcr.io/enrichmeai/cistern:0.1.0"

  validation {
    # A moving tag makes a redeploy irreproducible and a rollback a guess.
    condition     = can(regex(":[^:/]+$", var.image)) && !endswith(var.image, ":latest")
    error_message = "image must carry a pinned tag (ghcr.io/enrichmeai/cistern:0.1.0), never :latest and never untagged."
  }
}

variable "env_secret_name" {
  description = <<-EOT
    Name of the Secret Manager secret holding Cistern's authentication environment as an
    env file (KEY=VALUE per line): CISTERN_OWNER_WEBID, CISTERN_AUTH_OIDC_ISSUER,
    CISTERN_AUTH_OIDC_AUDIENCES, CISTERN_AUTH_SERVICEPRINCIPALS_0_WEBID, ... — see README.
    Terraform creates the secret and the instance's permission to read it; the VALUE is
    added out of band with `gcloud secrets versions add`, so it is never in tfvars or state.
    CISTERN_OWNER_TOKEN must not appear in it (ADR 0002 condition 3).
  EOT
  type        = string
  default     = ""
}

variable "audit_required" {
  description = "cistern.audit.required: a decision the log cannot record is not acted on (503). ADR 0002 condition 8 says true in production."
  type        = bool
  default     = true
}

variable "java_opts" {
  description = "JAVA_OPTS for the container. The default sizes the heap from the container's memory, not the host's."
  type        = string
  default     = "-XX:MaxRAMPercentage=75.0"
}

variable "pod_port" {
  description = "Port the pod listens on inside the instance. Only the load balancer and IAP can reach it."
  type        = number
  default     = 3000
}

variable "snapshot_start_hour" {
  description = "Hour (UTC, 0-23) at which the daily disk snapshot is taken. Pick the quietest."
  type        = number
  default     = 3

  validation {
    condition     = var.snapshot_start_hour >= 0 && var.snapshot_start_hour <= 23
    error_message = "snapshot_start_hour must be 0-23 (UTC)."
  }
}

variable "snapshot_retention_days" {
  description = "How many days of daily snapshots to keep."
  type        = number
  default     = 14

  validation {
    condition     = var.snapshot_retention_days >= 1
    error_message = "snapshot_retention_days must be at least 1."
  }
}

variable "rate_limit_per_minute" {
  description = <<-EOT
    Cloud Armor per-client-IP rate limit on the load balancer (ADR 0002 condition 7). Requests
    beyond this many per minute from one address get 429 at the edge. 0 disables the policy
    (and its ~USD 6/month), which is acceptable only behind an allowlist you control.
  EOT
  type        = number
  default     = 300

  validation {
    condition     = var.rate_limit_per_minute >= 0
    error_message = "rate_limit_per_minute must be 0 (off) or positive."
  }
}
