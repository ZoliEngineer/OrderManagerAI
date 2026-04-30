Review the entire OrderManagerAI application across all layers. Produce a structured report with the following sections:

---

## 1. Requirements Coverage

Read `docs/requirements.md` carefully. For each service and feature defined there, determine whether it has been implemented in the codebase. Report as a table:

| Component | Defined in Requirements | Implemented | Notes |
|-----------|------------------------|-------------|-------|

Mark each item as: Implemented / Partially Implemented / Not Started.

---

## 2. Backend Code Quality (Spring Boot / Spring WebFlux — `marketdata/`)

Review all Java source files under `marketdata/src/main/java/` and `marketdata/src/test/`. Evaluate:

- **Correctness**: Does the code do what it claims? Are there bugs, race conditions, or logical errors?
- **Spring best practices**: Proper use of dependency injection, bean lifecycle, reactive types (Mono/Flux used correctly, no blocking calls on reactive threads), correct use of `@Service`, `@Component`, `@Controller`/`@RestController`.
- **Security**: JWT/OAuth2 configuration correctness, no hardcoded secrets, CORS configured safely, WebSocket token handling secure.
- **Error handling**: Are exceptions handled properly? Are reactive error operators used (`.onErrorResume`, `.doOnError`)?
- **Testing**: Do tests cover the main service logic and controller endpoints? Are mocks used appropriately? Any missing critical test cases?
- **Configuration**: Is `application.properties` correctly structured? Are secrets coming from env vars only?
- **Code smells**: Dead code, unused imports, overly complex methods, missing null checks where needed.

---

## 3. Frontend Code Quality (React — `frontend/src/`)

Review all source files under `frontend/src/`. Evaluate:

- **Correctness**: Does auth flow work correctly with MSAL? Is the WebSocket lifecycle managed properly (connect, reconnect, cleanup on unmount)?
- **React best practices**: Hooks used correctly (no stale closures, correct dependency arrays in `useEffect`), state management is clean, no unnecessary re-renders.
- **Security**: No tokens stored in localStorage, no XSS vectors, API calls use HTTPS in production config.
- **Error handling**: Are API errors and WebSocket disconnects handled gracefully in the UI?
- **Code smells**: Unused components, prop drilling that should be context, hardcoded values that should be config.

---

## 4. Infrastructure & CI/CD (`terraform/`, `.github/workflows/`)

Review Terraform files and GitHub Actions workflows. Evaluate:

- **Security**: Are secrets sourced from Key Vault? Are managed identities used instead of credentials? Are network rules appropriately restrictive?
- **Correctness**: Do the Container App definitions match what the application expects (ports, env vars, health probes)?
- **CI/CD**: Do the workflows cover build, test, and deploy correctly? Is there a smoke test? Are Docker images tagged properly?
- **Best practices**: Is Terraform state stored remotely? Are resources tagged? Is `min_replicas = 0` appropriate for the use case?

---

## 5. Overall Architecture Adherence

Compare the implemented architecture against `docs/architecture.md`. Flag any deviations, missing pieces, or components that are stubbed but not functional.

---

## 6. Summary

Provide a prioritised list of findings:

**Critical** — correctness bugs, security issues, broken functionality  
**High** — missing requirement coverage, significant best-practice violations  
**Medium** — code quality issues, missing tests, minor design concerns  
**Low** — cosmetic issues, minor improvements  

For each finding include: location (file + line if possible), description, and recommended fix.
