terraform {
  required_version = ">= 1.5.0"

  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "~> 6.0"
    }
  }

  # Partial configuration: the bucket is created once, out of band, before this
  # ever runs (see README). Keeping it out of the code is what lets CI run
  # `init -backend=false` on a pull request with no credentials at all.
  backend "gcs" {}
}

provider "google" {
  project = var.project_id
  region  = var.region
}
