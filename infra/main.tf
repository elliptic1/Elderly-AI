
provider "google" {
  project = var.project_id
  region  = var.region
}

resource "google_project" "default" {
  project_id = var.project_id
  name       = var.project_id
  org_id     = var.org_id
}

resource "google_project_service" "firebase" {
  project = google_project.default.project_id
  service = "firebase.googleapis.com"
}

resource "google_project_service" "firestore" {
  project = google_project.default.project_id
  service = "firestore.googleapis.com"
}

resource "google_project_service" "cloudfunctions" {
  project = google_project.default.project_id
  service = "cloudfunctions.googleapis.com"
}

resource "google_project_service" "identitytoolkit" {
  project = google_project.default.project_id
  service = "identitytoolkit.googleapis.com"
}
