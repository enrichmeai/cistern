# One Container-Optimized OS instance running the Cistern image with the pod on an attached
# persistent disk, behind a global HTTPS load balancer with a Google-managed certificate.
# The posture is ADR 0002's: TLS terminated in front, 443 and nothing else, no public
# address on the instance, credentials from Secret Manager, daily snapshots. See README.md.

locals {
  base_url        = "https://${var.domain}"
  env_secret_name = var.env_secret_name != "" ? var.env_secret_name : "${var.name}-env"
  instance_tag    = "${var.name}-pod"

  data_mount  = "/mnt/disks/cistern-data"
  disk_device = "cistern-data"

  # Google's front-end proxy and health-check ranges: the only sources allowed to reach the
  # pod port. Fixed by Google, documented, and not a wildcard.
  # https://cloud.google.com/load-balancing/docs/https#firewall-rules
  lb_source_ranges = ["130.211.0.0/22", "35.191.0.0/16"]

  # IAP TCP forwarding: SSH and the tunnel for smoke tests, without a public port.
  iap_source_range = "35.235.240.0/20"

  # Runs as root on every boot. It (1) mounts the data disk, formatting it on first boot only,
  # (2) fetches the authentication environment from Secret Manager onto tmpfs, and (3) replaces
  # the container. Replacing rather than starting means a changed image tag takes effect on the
  # next reset with no second mechanism to reason about.
  #
  # Why not the COS container declaration (konlet, as before T7.7): it supports exactly one
  # container and takes environment only from instance metadata, which is readable by anyone
  # with compute.instances.get on the project and by every process on the VM. Secret Manager
  # plus a plain `docker run` keeps the credential hash and the issuer configuration out of
  # metadata, out of tfvars and out of state.
  startup_script = <<-EOT
    #!/bin/bash
    set -euo pipefail
    DISK="/dev/disk/by-id/google-${local.disk_device}"
    MOUNT="${local.data_mount}"

    # ---- data disk -----------------------------------------------------------------
    mkdir -p "$MOUNT"
    # Format once, on first boot only: blkid succeeds precisely when a filesystem already
    # exists, so this cannot silently wipe the pod on a later reboot.
    if ! blkid "$DISK" >/dev/null 2>&1; then
      mkfs.ext4 -F "$DISK"
    fi
    if ! mountpoint -q "$MOUNT"; then
      mount -o discard,defaults "$DISK" "$MOUNT"
    fi
    # uid/gid of the non-root user baked into the image.
    chown -R 10001:10001 "$MOUNT"

    # ---- authentication environment, from Secret Manager -----------------------------
    # Onto tmpfs (/run), root-only, re-read on every boot: rotate by adding a secret version
    # and resetting the instance. Never on the data disk (it is in every snapshot) and never
    # in metadata (it is readable project-wide).
    ENV_DIR=/run/cistern
    ENV_FILE=$ENV_DIR/env
    mkdir -p "$ENV_DIR" && chmod 700 "$ENV_DIR"
    TOKEN=$(curl -sf -H 'Metadata-Flavor: Google' \
      'http://metadata.google.internal/computeMetadata/v1/instance/service-accounts/default/token' \
      | tr -d '\n' | sed -E 's/.*"access_token" *: *"([^"]+)".*/\1/')
    curl -sf -H "Authorization: Bearer $TOKEN" \
      "https://secretmanager.googleapis.com/v1/projects/${var.project_id}/secrets/${local.env_secret_name}/versions/latest:access" \
      | tr -d '\n' | sed -E 's/.*"data" *: *"([^"]+)".*/\1/' | base64 -d > "$ENV_FILE"
    chmod 600 "$ENV_FILE"
    # ADR 0002 condition 3, checked at the last moment: the local bearer token is for a private
    # network and has no place behind a public address.
    if grep -q '^CISTERN_OWNER_TOKEN=' "$ENV_FILE"; then
      echo "refusing to start: CISTERN_OWNER_TOKEN is set in secret ${local.env_secret_name} (ADR 0002 condition 3)" >&2
      exit 1
    fi

    # ---- the container ---------------------------------------------------------------
    docker rm -f cistern >/dev/null 2>&1 || true
    docker pull "${var.image}"
    docker run -d --name cistern --restart=always \
      --read-only --tmpfs /tmp \
      --cap-drop=ALL --security-opt=no-new-privileges \
      --log-driver=json-file --log-opt max-size=10m --log-opt max-file=3 \
      -p ${var.pod_port}:3000 \
      -v "$MOUNT":/data \
      --env-file "$ENV_FILE" \
      -e CISTERN_STORAGE_ROOT=/data \
      -e CISTERN_BASE_URL="${local.base_url}" \
      -e CISTERN_AUDIT_REQUIRED="${var.audit_required}" \
      -e JAVA_OPTS="${var.java_opts}" \
      "${var.image}"
  EOT
}

# ---------------------------------------------------------------------------------------
# APIs. Idempotent; never disabled on destroy, because other things in the project may use them.
# ---------------------------------------------------------------------------------------

resource "google_project_service" "compute" {
  service            = "compute.googleapis.com"
  disable_on_destroy = false
}

resource "google_project_service" "iam" {
  service            = "iam.googleapis.com"
  disable_on_destroy = false
}

resource "google_project_service" "secretmanager" {
  service            = "secretmanager.googleapis.com"
  disable_on_destroy = false
}

# ---------------------------------------------------------------------------------------
# Identity and secrets
# ---------------------------------------------------------------------------------------

# Least privilege: the pod needs one Google API — reading its own secret. It gets an identity
# so it is not running as the default service account, which is broadly privileged.
resource "google_service_account" "pod" {
  account_id   = "${var.name}-sa"
  display_name = "Cistern pod (${var.name})"
  depends_on   = [google_project_service.iam]
}

# The container, not the value. Add versions with `gcloud secrets versions add` (README);
# Terraform never sees the contents and they never enter state.
resource "google_secret_manager_secret" "env" {
  secret_id = local.env_secret_name

  replication {
    auto {}
  }

  labels = {
    component = "cistern"
  }

  depends_on = [google_project_service.secretmanager]
}

resource "google_secret_manager_secret_iam_member" "pod_reads_env" {
  secret_id = google_secret_manager_secret.env.id
  role      = "roles/secretmanager.secretAccessor"
  member    = "serviceAccount:${google_service_account.pod.email}"
}

# ---------------------------------------------------------------------------------------
# Storage: the data disk and its snapshot schedule
# ---------------------------------------------------------------------------------------

resource "google_compute_disk" "data" {
  name = "${var.name}-data"
  type = "pd-balanced"
  zone = var.zone
  size = var.data_disk_gb

  labels = {
    component = "cistern"
  }

  lifecycle {
    # The pod lives here. Never let a routine plan destroy it.
    prevent_destroy = true
  }

  depends_on = [google_project_service.compute]
}

# Daily, crash-consistent, kept for N days, kept even if the disk is deleted. A persistent-disk
# snapshot is a point-in-time image of the blocks; because the file backend writes
# tmp-then-ATOMIC_MOVE, any such image contains each resource either before or after a write,
# never torn. Restore: README and infra/restore-drill.sh.
resource "google_compute_resource_policy" "daily_snapshot" {
  name   = "${var.name}-daily-snapshot"
  region = var.region

  snapshot_schedule_policy {
    schedule {
      daily_schedule {
        days_in_cycle = 1
        start_time    = format("%02d:00", var.snapshot_start_hour)
      }
    }
    retention_policy {
      max_retention_days    = var.snapshot_retention_days
      on_source_disk_delete = "KEEP_AUTO_SNAPSHOTS"
    }
    snapshot_properties {
      storage_locations = [var.region]
      guest_flush       = false
      labels = {
        component = "cistern"
      }
    }
  }

  depends_on = [google_project_service.compute]
}

resource "google_compute_disk_resource_policy_attachment" "daily_snapshot" {
  name = google_compute_resource_policy.daily_snapshot.name
  disk = google_compute_disk.data.name
  zone = var.zone
}

# ---------------------------------------------------------------------------------------
# The instance. No public address: the load balancer is the only way in, Cloud NAT the way out.
# ---------------------------------------------------------------------------------------

resource "google_compute_instance" "pod" {
  name         = var.name
  machine_type = var.machine_type
  zone         = var.zone
  tags         = [local.instance_tag]

  boot_disk {
    initialize_params {
      image = "cos-cloud/cos-stable"
      size  = 20
    }
  }

  attached_disk {
    source      = google_compute_disk.data.id
    device_name = local.disk_device
    mode        = "READ_WRITE"
  }

  network_interface {
    network = "default"
    # No access_config block: no external IP. There is nothing to scan and nothing to
    # firewall except the load balancer's and IAP's ranges.
  }

  metadata = {
    startup-script         = local.startup_script
    google-logging-enabled = "true"
    enable-oslogin         = "TRUE"
  }

  service_account {
    email = google_service_account.pod.email
    # cloud-platform scope, IAM does the restricting: the account can read one secret and
    # write logs, and nothing else, because that is all it has been granted.
    scopes = ["https://www.googleapis.com/auth/cloud-platform"]
  }

  shielded_instance_config {
    enable_secure_boot          = true
    enable_vtpm                 = true
    enable_integrity_monitoring = true
  }

  labels = {
    component = "cistern"
  }

  allow_stopping_for_update = true

  depends_on = [google_secret_manager_secret_iam_member.pod_reads_env]
}

# Egress without a public address: image pulls from GHCR and JWKS fetches from the OIDC
# issuer go out through NAT.
resource "google_compute_router" "egress" {
  name    = "${var.name}-router"
  region  = var.region
  network = "default"

  depends_on = [google_project_service.compute]
}

resource "google_compute_router_nat" "egress" {
  name                               = "${var.name}-nat"
  router                             = google_compute_router.egress.name
  region                             = var.region
  nat_ip_allocate_option             = "AUTO_ONLY"
  source_subnetwork_ip_ranges_to_nat = "ALL_SUBNETWORKS_ALL_IP_RANGES"

  log_config {
    enable = true
    filter = "ERRORS_ONLY"
  }
}

# ---------------------------------------------------------------------------------------
# Firewall: the pod port from the load balancer's ranges; SSH and the pod port from IAP.
# Nothing from anywhere else, and no rule for port 80 or 443 on the instance at all — the
# load balancer owns those.
# ---------------------------------------------------------------------------------------

resource "google_compute_firewall" "lb" {
  name    = "${var.name}-allow-lb"
  network = "default"

  allow {
    protocol = "tcp"
    ports    = [tostring(var.pod_port)]
  }

  source_ranges = local.lb_source_ranges
  target_tags   = [local.instance_tag]

  depends_on = [google_project_service.compute]
}

resource "google_compute_firewall" "iap" {
  name    = "${var.name}-allow-iap"
  network = "default"

  allow {
    protocol = "tcp"
    ports    = ["22", tostring(var.pod_port)]
  }

  source_ranges = [local.iap_source_range]
  target_tags   = [local.instance_tag]

  depends_on = [google_project_service.compute]
}

# ---------------------------------------------------------------------------------------
# TLS in front: a global external HTTPS load balancer with a Google-managed certificate.
#
# Chosen over a Caddy sidecar on the instance because (a) the COS container declaration runs
# one container, so a sidecar means a second orchestration mechanism on the VM; (b) a
# managed certificate needs no port 80, no ACME state on the data disk and no renewal cron;
# (c) the instance keeps no public address; (d) Cloud Armor's rate limit (condition 7)
# attaches here and nowhere else. The cost is a forwarding rule (~USD 18/month) — see README.
# ---------------------------------------------------------------------------------------

resource "google_compute_global_address" "lb" {
  name       = "${var.name}-lb-ip"
  depends_on = [google_project_service.compute]
}

resource "google_compute_managed_ssl_certificate" "lb" {
  name = "${var.name}-cert"

  managed {
    domains = [var.domain]
  }

  depends_on = [google_project_service.compute]
}

resource "google_compute_ssl_policy" "modern" {
  name            = "${var.name}-tls"
  profile         = "MODERN"
  min_tls_version = "TLS_1_2"

  depends_on = [google_project_service.compute]
}

# TCP, deliberately (as the Dockerfile HEALTHCHECK and the k8s probes are): with an owner
# configured the root answers 401 to an anonymous GET, and before pods are provisioned it
# answers 404 — an HTTP health check would call a healthy, enforcing server dead.
resource "google_compute_health_check" "pod" {
  name                = "${var.name}-tcp"
  check_interval_sec  = 10
  timeout_sec         = 5
  healthy_threshold   = 2
  unhealthy_threshold = 3

  tcp_health_check {
    port = var.pod_port
  }

  depends_on = [google_project_service.compute]
}

resource "google_compute_instance_group" "pod" {
  name      = "${var.name}-ig"
  zone      = var.zone
  instances = [google_compute_instance.pod.self_link]

  named_port {
    name = "http"
    port = var.pod_port
  }
}

resource "google_compute_backend_service" "pod" {
  name                  = "${var.name}-backend"
  protocol              = "HTTP"
  port_name             = "http"
  load_balancing_scheme = "EXTERNAL_MANAGED"
  timeout_sec           = 60
  health_checks         = [google_compute_health_check.pod.id]

  backend {
    group           = google_compute_instance_group.pod.self_link
    balancing_mode  = "UTILIZATION"
    capacity_scaler = 1.0
  }

  # Every request logged at the edge, so an access-log line and a receipt can be joined by
  # X-Request-Id (ADR 0002 condition 6). Turn the sample rate down once volume warrants it.
  log_config {
    enable      = true
    sample_rate = 1.0
  }

  security_policy = var.rate_limit_per_minute > 0 ? google_compute_security_policy.edge[0].id : null
}

resource "google_compute_url_map" "lb" {
  name            = "${var.name}-lb"
  default_service = google_compute_backend_service.pod.id
}

resource "google_compute_target_https_proxy" "lb" {
  name             = "${var.name}-https"
  url_map          = google_compute_url_map.lb.id
  ssl_certificates = [google_compute_managed_ssl_certificate.lb.id]
  ssl_policy       = google_compute_ssl_policy.modern.id
}

# 443 only. There is no port-80 listener and no redirect: clients are applications that are
# told the https URL, and a listener that exists only to redirect is a second forwarding rule
# to pay for and a plaintext hop to explain.
resource "google_compute_global_forwarding_rule" "https" {
  name                  = "${var.name}-https"
  target                = google_compute_target_https_proxy.lb.id
  ip_address            = google_compute_global_address.lb.address
  ip_protocol           = "TCP"
  port_range            = "443"
  load_balancing_scheme = "EXTERNAL_MANAGED"
}

# Rate limiting at the edge (ADR 0002 condition 7): per client IP, 429 beyond the threshold.
resource "google_compute_security_policy" "edge" {
  count = var.rate_limit_per_minute > 0 ? 1 : 0

  name        = "${var.name}-edge"
  description = "Per-client-IP rate limit for the Cistern pod (ADR 0002 condition 7)."

  rule {
    action      = "throttle"
    priority    = 1000
    description = "throttle every client to rate_limit_per_minute"

    match {
      versioned_expr = "SRC_IPS_V1"
      config {
        src_ip_ranges = ["*"]
      }
    }

    rate_limit_options {
      conform_action = "allow"
      exceed_action  = "deny(429)"
      enforce_on_key = "IP"

      rate_limit_threshold {
        count        = var.rate_limit_per_minute
        interval_sec = 60
      }
    }
  }

  rule {
    action      = "allow"
    priority    = 2147483647
    description = "default"

    match {
      versioned_expr = "SRC_IPS_V1"
      config {
        src_ip_ranges = ["*"]
      }
    }
  }

  depends_on = [google_project_service.compute]
}
