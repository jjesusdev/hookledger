locals {
  dlq_name      = "${var.name}-events-dlq"
  queue_name    = "${var.name}-events"
  alarm_actions = var.alarm_topic_arn == "" ? [] : [var.alarm_topic_arn]
}

resource "aws_sqs_queue" "dlq" {
  name                       = local.dlq_name
  message_retention_seconds  = 1209600
  visibility_timeout_seconds = var.visibility_timeout_seconds
}

resource "aws_sqs_queue" "events" {
  name                       = local.queue_name
  message_retention_seconds  = var.message_retention_seconds
  visibility_timeout_seconds = var.visibility_timeout_seconds

  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.dlq.arn
    maxReceiveCount     = var.max_receive_count
  })
}

resource "aws_iam_policy" "app" {
  name        = "${var.name}-sqs"
  description = "Send, consume, retry and replay ${var.name} webhook messages"

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Action = [
          "sqs:SendMessage",
          "sqs:ReceiveMessage",
          "sqs:DeleteMessage",
          "sqs:ChangeMessageVisibility",
          "sqs:GetQueueAttributes",
          "sqs:GetQueueUrl"
        ]
        Resource = [
          aws_sqs_queue.events.arn,
          aws_sqs_queue.dlq.arn
        ]
      }
    ]
  })
}

resource "aws_cloudwatch_metric_alarm" "dlq_not_empty" {
  alarm_name          = "${var.name}-dlq-not-empty"
  alarm_description   = "Webhook deliveries are landing in the DLQ."
  namespace           = "AWS/SQS"
  metric_name         = "ApproximateNumberOfMessagesVisible"
  dimensions          = { QueueName = aws_sqs_queue.dlq.name }
  statistic           = "Maximum"
  period              = 300
  evaluation_periods  = 1
  threshold           = 0
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions
}

resource "aws_cloudwatch_metric_alarm" "oldest_message" {
  alarm_name          = "${var.name}-oldest-message"
  alarm_description   = "The oldest webhook message is older than five minutes."
  namespace           = "AWS/SQS"
  metric_name         = "ApproximateAgeOfOldestMessage"
  dimensions          = { QueueName = aws_sqs_queue.events.name }
  statistic           = "Maximum"
  period              = 300
  evaluation_periods  = 1
  threshold           = 300
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions
}
