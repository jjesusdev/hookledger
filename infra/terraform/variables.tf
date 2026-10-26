variable "name" {
  description = "Prefix for the queues and alarms."
  type        = string
  default     = "hookledger"
}

variable "region" {
  description = "AWS region for the queues."
  type        = string
  default     = "us-east-1"
}

variable "max_receive_count" {
  description = "Attempts before SQS moves a message to the DLQ. Must match hookledger.worker.max-receives."
  type        = number
  default     = 3

  validation {
    condition     = var.max_receive_count >= 1 && var.max_receive_count <= 1000
    error_message = "max_receive_count must be between 1 and 1000."
  }
}

variable "visibility_timeout_seconds" {
  description = "How long a worker owns a message before SQS redelivers it."
  type        = number
  default     = 60
}

variable "message_retention_seconds" {
  description = "Retention for the main queue. The DLQ keeps messages for 14 days."
  type        = number
  default     = 345600
}

variable "alarm_topic_arn" {
  description = "Optional SNS topic for alarms. Leave empty to create the alarms without notifications."
  type        = string
  default     = ""
}
