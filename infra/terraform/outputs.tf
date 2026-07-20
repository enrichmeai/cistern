output "instance_name" {
  description = "Name of the pod instance."
  value       = google_compute_instance.pod.name
}

output "zone" {
  description = "Zone the instance runs in."
  value       = google_compute_instance.pod.zone
}

output "external_ip" {
  description = "Public address, or null when private (the default)."
  value = try(
    google_compute_instance.pod.network_interface[0].access_config[0].nat_ip,
    null
  )
}

output "tunnel_command" {
  description = "Reach the pod on localhost:3000 without exposing any port."
  value       = "gcloud compute start-iap-tunnel ${google_compute_instance.pod.name} ${var.pod_port} --local-host-port=localhost:3000 --zone=${var.zone} --project=${var.project_id}"
}

output "ssh_command" {
  description = "Shell on the instance over IAP (no public IP required)."
  value       = "gcloud compute ssh ${google_compute_instance.pod.name} --tunnel-through-iap --zone=${var.zone} --project=${var.project_id}"
}
