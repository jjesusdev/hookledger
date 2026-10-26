.PHONY: test package run up down logs demo

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

logs:
	docker compose logs -f app

demo:
	./scripts/send-event.sh
