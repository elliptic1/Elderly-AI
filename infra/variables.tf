
variable "project_id" {
  description = "The ID of the Google Cloud project."
  type        = string
}

variable "region" {
  description = "The region where the Google Cloud resources will be created."
  type        = string
  default     = "us-central1"
}

variable "org_id" {
  description = "The ID of the Google Cloud organization."
  type        = string
}
