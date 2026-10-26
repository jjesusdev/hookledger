.PHONY: test package run up down up-sqs down-sqs logs demo

test:
	mvn -B -ntp verify

package:
	mvn -B -ntp -DskipTests package

run:
	mvn -B -ntp spring-boot:run

up:
	docker compose up --build -d

down:
	docker compose down -v

up-sqs:
	docker compose -f docker-compose.yml -f docker-compose.sqs.yml up --build -d

down-sqs:
	docker compose -f docker-compose.yml -f docker-compose.sqs.yml down -v

logs:
	docker compose logs -f app

demo:
	./scripts/send-event.sh
