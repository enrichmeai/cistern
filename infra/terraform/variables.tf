variable "project_id" {
  description = "GCP project that hosts the test pod."
  type        = string
}

variable "region" {
  description = "Region for regional resources."
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
  default     = "cistern-test"
}

variable "machine_type" {
  description = "Instance size. e2-small is ample for a test pod."
  type        = string
  default     = "e2-small"
}

variable "data_disk_gb" {
  description = <<-EOT
    Size of the persistent disk holding the pod. A real filesystem is required:
    the storage backend writes tmp-then-ATOMIC_MOVE, so bucket-style storage
    (gcsfuse renames are copy-then-delete) would void its crash-safety guarantee.
  EOT
  type        = number
  default     = 10
}

variable "image" {
  description = "Container image to run. Published on a v* tag by the CI image job."
  type        = string
  default     = "ghcr.io/enrichmeai/cistern:latest"
}

variable "base_url" {
  description = <<-EOT
    Value for cistern.base-url. It mints Location headers and the storage
    description, so it must be the URL clients actually call. With the default
    private posture that is the local end of the IAP tunnel.
  EOT
  type        = string
  default     = "http://localhost:3000"
}

# ---------------------------------------------------------------------------
# Exposure. Defaults are closed on purpose.
#
# Cistern has no authentication and no access control until Phase 5: an
# anonymous request can create a resource (201) and delete one (204). ADR 0001
# therefore keeps it unreachable. These defaults mean that doing nothing yields
# the safe configuration, rather than a documented intention to be careful.
# ---------------------------------------------------------------------------

variable "enable_external_ip" {
  description = "Give the instance a public address. Leave false until Phase 5."
  type        = bool
  default     = false
}

variable "allowed_ingress_cidrs" {
  description = <<-EOT
    Extra CIDRs allowed to reach the pod port directly. Empty by default —
    access is via `gcloud compute start-iap-tunnel`, which needs no open port.
    Only ever your own addresses, and only for as long as you need them.
  EOT
  type        = list(string)
  default     = []

  validation {
    # Refuse to put an anonymously writable data store on the open internet.
    # Not advice in a README — the plan fails.
    condition = !contains([
      for cidr in var.allowed_ingress_cidrs : cidr
    ], "0.0.0.0/0")
    error_message = "0.0.0.0/0 is not allowed: Cistern has no authorization layer yet (ADR 0001). An open port here is an anonymously writable pod."
  }
}

variable "pod_port" {
  description = "Port the pod listens on."
  type        = number
  default     = 3000
}
