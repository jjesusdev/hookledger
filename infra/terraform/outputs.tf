output "queue_url" {
  value = aws_sqs_queue.events.url
}

output "queue_arn" {
  value = aws_sqs_queue.events.arn
}

output "dlq_url" {
  value = aws_sqs_queue.dlq.url
}

output "dlq_arn" {
  value = aws_sqs_queue.dlq.arn
}

output "app_policy_arn" {
  description = "Attach this policy to the EC2 instance role or IRSA role that runs the app and worker."
  value       = aws_iam_policy.app.arn
}
