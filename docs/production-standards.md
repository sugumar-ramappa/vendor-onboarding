# Production standards

*"Production code, not a demo"* is a real requirement, so it needs to be
specific. This is the checklist the project is held to, and the reason for each.

An interviewer probes exactly here: **"what happens when the model times out?"**,
**"how do you know which prompt produced this finding?"**, **"what if two
requests arrive for the same application?"** A demo has no answer. Each item
below is one.

---

## 1. Failure is the normal path, not the exception

The Gemini free tier **will** return 429. A demo discovers this during the demo.

| Concern | Standard |
|---|---|
| Timeouts | Explicit connect and read timeouts on every model call. Never the default |
| Retries | Exponential backoff, capped attempts, and **429 handling that distinguishes per-minute from per-day** — one clears in a minute, the other at midnight |
| Circuit breaker | Repeated model failure stops calling and degrades, rather than queueing work that cannot succeed |
| **Fail closed** | If a reviewer cannot run, the application **escalates to a human**. It never returns "no findings", because absence of findings is indistinguishable from approval |

That last row is the one that matters. **An AI system that fails open is worse
than no system**, because it produces silence that reads as a clean review.

## 2. Every model interaction is auditable

```java
record ModelCall(
    UUID correlationId,
    String applicationId,
    String nodeName,
    String promptVersion,     // ← the one demos never have
    String modelName,
    Instant startedAt,
    Duration latency,
    int promptTokens,
    int completionTokens,
    Outcome outcome
) {}
```

**`promptVersion` is the production tell.** Prompts change. Without recording
which version produced a finding, you cannot explain why last month's result
differs from this month's, and you cannot roll back a regression. Prompts live in
version-controlled resources with an explicit version, not in string literals.

Every finding carries the correlation ID of the call that produced it. Given a
finding, you can retrieve the exact prompt and the exact response.

## 3. Configuration is typed and validated at startup

```java
@ConfigurationProperties("onboarding.policy")
@Validated
public record PolicyProperties(
    @NotNull Severity blockingSeverity,
    @Positive int minCertificateValidityDays,
    @Positive long minPublicLiability
) {}
```

Fails at startup with a clear message, not at 3am with a `NumberFormatException`.
No `@Value("${...}")` scattered through the codebase.

## 4. Idempotency and concurrency

Applications arrive over HTTP, and HTTP is retried.

- Submission takes an **idempotency key**; a repeat returns the original result
  rather than re-running five agents and re-spending quota
- Review runs hold an application-level lock, so two concurrent submissions for
  the same vendor cannot interleave
- Graph state is checkpointed, so a crashed run resumes rather than restarting

## 5. Boundaries are explicit

```
api/       DTOs, validation, error mapping   ← HTTP shapes live here and nowhere else
domain/    records, no framework annotations ← the part worth keeping
graph/     orchestration
agents/    model-facing adapters
guard/     the security layer
authority/ deterministic decisions, no LLM
mcp/       read-only tool exposure
```

Domain records never leak into the API and API DTOs never reach the model layer.
The reason is testability, not purity: the decision gate must be unit-testable
with no HTTP, no database and no model.

## 6. Errors are typed and mapped

```java
sealed interface ReviewFailure {
    record ModelUnavailable(String node, Duration waited)   implements ReviewFailure {}
    record QuarantinedInput(String documentId, String rule) implements ReviewFailure {}
    record BudgetExceeded(int tokensUsed, int limit)        implements ReviewFailure {}
    record EvidenceMissing(String findingId)                implements ReviewFailure {}
}
```

A `@RestControllerAdvice` maps these to HTTP status and a stable error body. **No
exception is caught and logged without either being handled or rethrown** — the
single most common way a demo hides its own bugs.

## 7. Tests that would fail if the system broke

| Level | What it covers |
|---|---|
| Unit | Decision gate, conflict detector, injection scanner. No model, no I/O |
| Integration | Graph runs end to end with a **stubbed model**, so it is deterministic and free |
| Contract | MCP tools return the documented shape |
| Persistence | Testcontainers Postgres, not H2 — the SQL that ships is the SQL tested |
| Adversarial | The seeded-attack fixtures, run in CI |

**Model calls are stubbed in tests.** A test suite that needs an API key and a
network is a test suite nobody runs. The measurement harness is separate, run
deliberately, and its results are committed.

## 8. Observability that answers operational questions

| Metric | Question it answers |
|---|---|
| `review.duration` by node | Which reviewer is slow? |
| `review.tokens` by node | Which reviewer costs the most? |
| `guardrail.triggered` by rule | Is a guardrail firing constantly — attack, or false positive? |
| `review.outcome` by verdict | What proportion escalate? Rising means drift |
| `model.failures` by type | Rate limit, timeout, or malformed output? |

Structured JSON logs with correlation ID on every line. Health check that reports
model reachability separately from database reachability, because they fail
independently.

## 9. Security is structural, not advisory

- Secrets from environment only. `.env` gitignored, `.env.example` committed
- Agents connect to Postgres through a **read-only role**; writes use a different
  connection reachable only from the decision gate
- No LLM output is ever interpolated into SQL, a shell command, or a file path
- Vendor documents are untrusted input at every entry point, including **tool
  results**, which re-enter the model after the pre-model gate has passed
- Guardrails mapped to OWASP LLM01, LLM05, LLM06, LLM09, LLM10 — each with a test

## 10. The build enforces it

- Compiler warnings on, `-Werror` for the domain module
- Static analysis in CI
- Test coverage reported, with the decision gate and guardrails held to a higher
  bar than the whole
- CI runs unit, integration and adversarial suites on every push
- Dependency versions pinned through BOMs; no version ranges

---

## What this changes about the plan

Roughly **+20%** on the estimate. That is the honest cost, and it is the right
trade — the difference between *"I built a multi-agent system"* and *"I built one
that fails closed, records which prompt produced each finding, and has an
idempotent submission path"* is the difference between a demo and something a
team could adopt.

## The three that carry the most weight in an interview

1. **Fail closed.** Most AI demos fail open and nobody notices until it matters.
2. **Prompt versioning.** Nobody does this, and everyone who has run an LLM
   system in production has wished they had.
3. **The read-only database role.** Guardrails in a prompt are advisory;
   a missing `GRANT` is not.
