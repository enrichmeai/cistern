output "base_url" {
  description = "The public origin, and the value of cistern.base-url: what clients call and what every minted identifier starts with."
  value       = local.base_url
}

output "load_balancer_ip" {
  description = "Point the domain's A record here. The managed certificate stays PROVISIONING until it resolves."
  value       = google_compute_global_address.lb.address
}

output "certificate_status_command" {
  description = "Watch the managed certificate go ACTIVE (minutes after DNS resolves; up to an hour)."
  value       = "gcloud compute ssl-certificates describe ${google_compute_managed_ssl_certificate.lb.name} --global --project=${var.project_id} --format='value(managed.status,managed.domainStatus)'"
}

output "env_secret" {
  description = "The Secret Manager secret the instance reads its authentication environment from. Add a version before the first boot."
  value       = google_secret_manager_secret.env.secret_id
}

output "env_secret_add_command" {
  description = "How to (re)load the authentication environment: write cistern.env locally, run this, then reset the instance."
  value       = "gcloud secrets versions add ${google_secret_manager_secret.env.secret_id} --project=${var.project_id} --data-file=cistern.env"
}

output "instance_name" {
  description = "Name of the pod instance."
  value       = google_compute_instance.pod.name
}

output "zone" {
  description = "Zone the instance and its disk run in."
  value       = google_compute_instance.pod.zone
}

output "data_disk" {
  description = "The disk holding cistern.storage.root — the unit of backup and restore."
  value       = google_compute_disk.data.name
}

output "snapshot_policy" {
  description = "The daily snapshot schedule attached to the data disk."
  value       = google_compute_resource_policy.daily_snapshot.name
}

output "upgrade_command" {
  description = "After changing `image` and applying: reboot so the startup script pulls and runs the new tag."
  value       = "gcloud compute instances reset ${google_compute_instance.pod.name} --zone=${var.zone} --project=${var.project_id}"
}

output "ssh_command" {
  description = "Shell on the instance over IAP (no public IP exists)."
  value       = "gcloud compute ssh ${google_compute_instance.pod.name} --tunnel-through-iap --zone=${var.zone} --project=${var.project_id}"
}

output "tunnel_command" {
  description = "Reach the pod port directly on localhost:3000, bypassing the load balancer, for a smoke test."
  value       = "gcloud compute start-iap-tunnel ${google_compute_instance.pod.name} ${var.pod_port} --local-host-port=localhost:3000 --zone=${var.zone} --project=${var.project_id}"
}
