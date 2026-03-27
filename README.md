# OrderManager AI

A Hello World application with a Spring Boot REST backend and React frontend, packaged with Maven.

## Prerequisites

- Java 21+
- Maven 3.8+
- Node.js 20+ and npm (for running the frontend in dev mode)

## Project Structure

```
OrderManagerAI/
├── backend/    # Spring Boot REST API (port 8080)
└── frontend/   # React app (port 3000)
```

## Build

Build both modules from the project root:

```bash
mvn package
```

This compiles the backend and builds the React frontend (the frontend Maven module downloads Node/npm automatically via the frontend-maven-plugin).

## Run

### Backend

```bash
cd backend
mvn spring-boot:run
```

The API will be available at:
- `GET http://localhost:8080/api/hello` — returns `Hello World!!!`
- `GET http://localhost:8080/actuator/health` — health check

### Frontend (dev mode)

Open a separate terminal:

```bash
cd frontend/src/main/frontend
npm install   # only needed the first time
npm start
```

The app will open at `http://localhost:3000` and display the message fetched from the backend.

> **Note:** Run the backend before starting the frontend. Use a **bash** terminal (Git Bash) on Windows — `npm` does not work in PowerShell by default due to execution policy restrictions.

## Tests

Run backend unit tests:

```bash
cd backend
mvn test
```
