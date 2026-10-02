# START HERE — Windows beginner guide

You do **not** need to install PostgreSQL or Maven separately if you use the supplied Docker setup.

For the simplest path, install only:

1. Git for Windows
2. Docker Desktop for Windows
3. Python is optional because a PowerShell burst script is also included.

If Java 17 is already installed on your machine, keep it. It is useful for IDE work, but Docker builds the application with Java 17 inside the image.

## 1. Install Git

Install Git for Windows, then open **PowerShell** and verify:

```powershell
git --version
```

## 2. Install Docker Desktop

Install Docker Desktop and start it. Leave it running while using the project.

Verify:

```powershell
docker --version
docker compose version
```

If Docker asks you to enable WSL 2, accept it and restart Windows if requested.

## 3. Extract the project

Extract the supplied ZIP to something simple, for example:

```text
C:\paytm-seat-reservation
```

Open PowerShell in that folder.

## 4. Start the complete application

Run:

```powershell
docker compose up --build
```

This starts:

- PostgreSQL
- the Spring Boot API
- the database schema automatically

The API is:

```text
http://localhost:8080
```

Keep this terminal open.

## 5. Check health

Open a second PowerShell window:

```powershell
curl.exe http://localhost:8080/live
curl.exe http://localhost:8080/ready
```

You should see `UP` responses.

## 6. Create a test show

Run:

```powershell
curl.exe -X POST http://localhost:8080/shows `
  -H "Authorization: Bearer admin-local" `
  -H "Content-Type: application/json" `
  -d '{"name":"interview-demo","seats":["A1","A2","A3","A4","A5","A6","A7","A8","A9","A10"],"price_paise":25000,"per_user_limit":4}'
```

The response contains an `id`. Copy that UUID; call it `SHOW_ID` below.

## 7. Make one reservation

Replace `SHOW_ID`:

```powershell
curl.exe -X POST http://localhost:8080/shows/SHOW_ID/reserve `
  -H "Authorization: Bearer user:alice:local-secret" `
  -H "Idempotency-Key: demo-1" `
  -H "Content-Type: application/json" `
  -d '{"seats":["A1"]}'
```

Then inspect the state:

```powershell
curl.exe http://localhost:8080/shows/SHOW_ID
```

## 8. Run the concurrency test

The easiest Windows option is the included PowerShell script:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\burst.ps1 http://localhost:8080 SHOW_ID -Mode hot-seat -Requests 100
```

Expected behavior:

- one request gets `201`
- competing requests get domain `409` responses
- no `5xx`
- reconciliation remains true

For a stronger local test:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\burst.ps1 http://localhost:8080 SHOW_ID -Mode hot-seat -Requests 500
```

Do not start with 20,000 requests on an 8 GB laptop. The assignment-scale burst is better run against a suitably sized deployed service after the local test passes.

## 9. Stop the application

In the Docker terminal press `Ctrl+C`.

To remove the containers and local database volume:

```powershell
docker compose down -v
```

Use `-v` only when you want to reset the database completely.

# GitHub — publish the code

Create a new **empty** GitHub repository, for example:

```text
seat-reservation-at-scale
```

Do not add a README or `.gitignore` on GitHub because this project already contains them.

Then in the project folder:

```powershell
git remote add origin https://github.com/YOUR_GITHUB_USERNAME/seat-reservation-at-scale.git
git branch -M main
git push -u origin main
```

Refresh GitHub. You should see the complete incremental commit history.

Never commit real database passwords, production tokens or private keys.

# Render — create the public interviewer URL

Render can deploy a Dockerfile-backed Web Service from a GitHub repository.

1. Create/sign in to Render.
2. Choose **New → Web Service**.
3. Connect GitHub and select this repository.
4. Set the runtime/language to **Docker**.
5. Choose a suitable plan.
6. Set the health-check path to `/ready`.
7. Create a PostgreSQL database on Render.
8. Put its JDBC connection details into the web service environment variables.
9. Set:

```text
DATABASE_URL=<Render PostgreSQL JDBC URL>
DB_USERNAME=<database username>
DB_PASSWORD=<database password>
AUTH_SECRET=<long random secret>
ADMIN_TOKEN=<long random admin token>
```

The application already reads the hosting provider's `PORT` environment variable.

After deployment, Render gives the Web Service an `onrender.com` URL. Test:

```text
https://YOUR-SERVICE.onrender.com/live
https://YOUR-SERVICE.onrender.com/ready
```

The first request on a free/sleeping service can take longer because the service may need to wake up.

## 10. Create the live demo show

Use the production `ADMIN_TOKEN` and your live URL. Do **not** put the token in GitHub or the submission message.

```powershell
curl.exe -X POST https://YOUR-SERVICE.onrender.com/shows `
  -H "Authorization: Bearer YOUR_ADMIN_TOKEN" `
  -H "Content-Type: application/json" `
  -d '{"name":"interview-demo","seats":["A1","A2","A3","A4","A5","A6","A7","A8","A9","A10"],"price_paise":25000,"per_user_limit":4}'
```

Copy the returned show ID.

## 11. What to send the interviewers

Send these three things:

1. **GitHub repository URL** — source code and commit history.
2. **Live API URL** — for example `https://your-service.onrender.com`.
3. **One-line note** explaining the main concurrency mechanism: PostgreSQL row locks + deterministic lock ordering + transaction-scoped per-user advisory lock + idempotency constraint.

Do not send database credentials or admin tokens.

## Important AI disclosure

The assignment asks for honest AI-usage disclosure. The repository contains a short AI-usage section describing how AI assisted with scaffolding/review. Do not claim that no AI was used if asked directly. The important thing is that you understand the code and can explain the concurrency decisions yourself.
