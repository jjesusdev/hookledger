#!/bin/sh
set -eu

# Runs inside LocalStack via /etc/localstack/init/ready.d/. `awslocal` targets the local endpoint.
awslocal sqs create-queue --queue-name hookledger-events-dlq >/dev/null

DLQ_URL=$(awslocal sqs get-queue-url --queue-name hookledger-events-dlq --query QueueUrl --output text)
DLQ_ARN=$(awslocal sqs get-queue-attributes \
  --queue-url "$DLQ_URL" \
  --attribute-names QueueArn \
  --query 'Attributes.QueueArn' \
  --output text)

awslocal sqs create-queue \
  --queue-name hookledger-events \
  --attributes '{"RedrivePolicy":"{\"deadLetterTargetArn\":\"'"$DLQ_ARN"'\",\"maxReceiveCount\":\"3\"}"}' >/dev/null

echo "localstack queues ready: hookledger-events, hookledger-events-dlq"
