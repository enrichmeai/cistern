# A single Container-Optimized OS instance running the Cistern image, with the pod
# on an attached persistent disk. Private by default — see variables.tf.

locals {
  # COS reads this and runs the container. One container, matching docker-compose.yml.
  container_declaration = yamlencode({
    spec = {
      restartPolicy = "Always"
      containers = [{
        name  = "cistern"
        image = var.image
        env = [
          { name = "CISTERN_STORAGE_ROOT", value = "/data" },
          { name = "CISTERN_BASE_URL", value = var.base_url },
        ]
        volumeMounts = [{
          name      = "data"
          mountPath = "/data"
          readOnly  = false
        }]
      }]
      volumes = [{
        name     = "data"
        hostPath = { path = local.data_mount }
      }]
    }
  })

  data_mount  = "/mnt/disks/cistern-data"
  disk_device = "cistern-data"

  # COS does not format or mount an attached disk on its own, and the image runs as
  # uid 10001, so the mountpoint has to be handed over before the container starts.
  startup_script = <<-EOT
    #!/bin/bash
    set -euo pipefail
    DISK="/dev/disk/by-id/google-${local.disk_device}"
    MOUNT="${local.data_mount}"

    mkdir -p "$MOUNT"
    # Format once, on first boot only: blkid succeeds precisely when a filesystem
    # already exists, so this cannot silently wipe the pod on a later reboot.
    if ! blkid "$DISK" >/dev/null 2>&1; then
      mkfs.ext4 -F "$DISK"
    fi
    if ! mountpoint -q "$MOUNT"; then
      mount -o discard,defaults "$DISK" "$MOUNT"
    fi
    # uid/gid of the non-root user baked into the image.
    chown -R 10001:10001 "$MOUNT"
  EOT
}

# Least privilege: the pod needs no Google APIs. It gets an identity solely so it is
# not running as the default service account, which is broadly privileged.
resource "google_service_account" "pod" {
  account_id   = "${var.name}-sa"
  display_name = "Cistern test pod"
}

resource "google_compute_disk" "data" {
  name = "${var.name}-data"
  type = "pd-balanced"
  zone = var.zone
  size = var.data_disk_gb

  lifecycle {
    # The pod lives here. Never let a routine plan destroy it.
    prevent_destroy = true
  }
}

resource "google_compute_instance" "pod" {
  name         = var.name
  machine_type = var.machine_type
  zone         = var.zone
  tags         = ["${var.name}-pod"]

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

    # No access_config block => no external IP. The dynamic block means the safe
    # shape is what you get from the defaults, not what you get if you remember.
    dynamic "access_config" {
      for_each = var.enable_external_ip ? [1] : []
      content {}
    }
  }

  metadata = {
    gce-container-declaration = local.container_declaration
    google-logging-enabled    = "true"
    startup-script            = local.startup_script
  }

  service_account {
    email  = google_service_account.pod.email
    scopes = ["https://www.googleapis.com/auth/logging.write"]
  }

  labels = {
    component = "cistern"
    purpose   = "test-pod"
  }

  allow_stopping_for_update = true
}

# IAP TCP forwarding: reach SSH and the pod port without exposing either. The range
# is Google's fixed IAP forwarders, not a wildcard.
resource "google_compute_firewall" "iap" {
  name    = "${var.name}-allow-iap"
  network = "default"

  allow {
    protocol = "tcp"
    ports    = ["22", tostring(var.pod_port)]
  }

  source_ranges = ["35.235.240.0/20"]
  target_tags   = ["${var.name}-pod"]
}

# Only created when someone explicitly lists their own address. 0.0.0.0/0 is
# rejected by a variable validation, so this cannot open to the world.
resource "google_compute_firewall" "direct" {
  count = length(var.allowed_ingress_cidrs) > 0 ? 1 : 0

  name    = "${var.name}-allow-direct"
  network = "default"

  allow {
    protocol = "tcp"
    ports    = [tostring(var.pod_port)]
  }

  source_ranges = var.allowed_ingress_cidrs
  target_tags   = ["${var.name}-pod"]
}
