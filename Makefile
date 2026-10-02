up:
	docker compose up --build

down:
	docker compose down

test:
	mvn test

build:
	mvn clean package

burst:
	python3 scripts/burst.py $${BASE_URL:-http://localhost:8080} $${SHOW_ID} $${N:-500}
