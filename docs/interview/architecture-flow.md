# Architecture flow

How a vendor application becomes a review pack, with the class and method at
each step.

Companion documents:
- [`code-walkthrough.md`](code-walkthrough.md) — what each class does
- [`testing.md`](testing.md) — how it is tested and measured

---

## The shape in one diagram

```mermaid
flowchart TD
    SUB["POST an application<br/>documents + SKU list"]

    SUB --> INTAKE
    subgraph INTAKE["INTAKE — no model involved"]
        TX["TextExtractor<br/><i>PDF · Excel · plain text</i>"]
        FX["FactExtractor<br/><i>dates · standards · amounts</i>"]
        TX --> FX
    end

    INTAKE --> IDEM{"ReviewService<br/>seen this pack before?"}
    IDEM -->|yes| STORED["stored result<br/><b>zero model calls</b>"]
    IDEM -->|no| GRAPH

    subgraph GRAPH["LangGraph4j"]
        direction TB
        G1["intake"]
        G2["5 reviewers, concurrent<br/><i>isolated contexts</i>"]
        G3["gather + ConflictDetector"]
        G4["verify<br/><i>grounding, then challenge</i>"]
        G1 --> G2 --> G3 --> G4
    end

    GRAPH --> REPO["ReviewRepository<br/><i>findings · evidence · conflicts · audit</i>"]
    REPO --> PACK["review pack → human"]

    style INTAKE fill:#1f2937,color:#fff
    style STORED fill:#14532d,color:#fff
```

---

## Why multi-agent, and not one prompt

The question every interviewer asks. Three of the four justifying conditions
hold here, which is unusual.

**It is already multi-party.** Compliance, quality, logistics, finance and
governance each review the same pack today, independently, against different
rulebooks. The agents model reviewers who exist — this is not an architecture
invented for the sake of it.

**The conclusions genuinely conflict.**

```
Logistics:   "EDI-capable, 3-day lead time — ready to onboard"
Quality:     "audit certificate expired four months ago"
Compliance:  "insurance covers the EU only; we ship to the UK"
```

Three valid positions, one decision. A single agent **averages that into a
paragraph and the trade-off disappears** — and the disagreement was the most
informative thing in the review.

**Independence has to be enforced.** One context that sees the expired
certificate first colours every later judgement. That is prevented twice:
`ReviewContext` has nowhere to put another reviewer's findings, and the agent
database role has no `SELECT` on `review_finding`. **The permission is the one
that survives someone editing the class.**

The fourth condition — cycles — is why LangGraph4j rather than
`CompletableFuture.allOf`. The verifier can send work back, and
`langgraph4j-postgres-saver` checkpoints state so a review can pause for a human
and resume.

---

## Stack

| Layer | Choice | Why |
|---|---|---|
| Language | Java 21 | records, sealed types, virtual threads |
| Framework | Spring Boot 4.1.1 | |
| AI | **Spring AI 2.0.1** | picked over LangChain4j for its **MCP server** starter |
| Orchestration | **LangGraph4j 1.8.24** | conditional edges, cycles, checkpointing |
| Model | `gemini-3.6-flash`, pinned | **not** `-latest`: an alias would swap the model under a measurement |
| Tools | MCP over a **read-only** Postgres role | |
| Documents | PDFBox, Apache POI, Gemini vision | |
| Persistence | Postgres 16 + Flyway | |

**Every version was read from `repo1.maven.org` metadata, not the Maven search
API** — which reported `langgraph4j 1.6.0-beta5` when `1.8.24` stable existed,
and changed the project estimate by four hours.

---

## Stage 1 — Intake, before any model

```
bytes → TextExtractor.extract()      → ExtractedText  (per page)
      → SkuSheetParser.parse()       → List<Sku>      (from .xlsx)
      → FactExtractor.extract()      → DocumentFacts  (dates, standards, amounts)
```

**The design principle the whole system rests on:**

| Operation | Time | Relative |
|---|---|---|
| Regex over document text | 0.1 ms | 1× |
| Parse a 400-row spreadsheet | 40 ms | 400× |
| **One model call** | **30,000 ms** | **300,000×** |

So the pipeline **parses first and asks second**. Every date, standard and amount
extracted by `FactExtractor` is a question the model never has to answer — and
models are unreliable at date arithmetic, so this buys accuracy as well as speed.

### Three routes in, with different trust

```
PDF with a text layer  →  PDFBox        →  NATIVE_TEXT    exact
Excel / plain text     →  POI / decode  →  NATIVE_TEXT    exact
scanned PDF            →  Gemini vision →  MODEL_VISION   probabilistic
```

`ExtractionSource` travels with every parsed value, and it changes what may be
concluded:

- `MODEL_VISION` **cannot** produce a `DETERMINISTIC` finding — the comparison is
  exact but the input was a model reading a possibly-smudged image
- its evidence cannot be grounded by substring match — there is no source text
- anything BLOCKING built on one goes to a human

### The fail-closed check worth naming

A scan is a valid PDF with pages and no text. Treat that as "an empty document"
and something backwards happens:

```
completeness → file is present   ✅ passes
compliance   → nothing to find   ✅ no findings
                                 ─────────────
                 vendor appears compliant because their
                 certificate was unreadable
```

`ExtractedText.hasNoTextLayer()` catches it. The page is read by vision, or the
document is rejected — never passed on.

---

## Stage 2 — Idempotency, before the expensive part

```java
ReviewService.submit(context)
    → idempotencyKey(context)          // sha256 of application + documents
    → repository.findByIdempotencyKey  // hit? return stored, ZERO model calls
    → graph.review(context)
    → repository.save(state, key)
```

**The check has to come before the work.** Running five reviewers, spending five
model calls and two minutes, then discovering the result was already stored would
make the check pointless.

The key is derived from content, so an unchanged pack is recognised even if the
caller forgot to send a key — and a corrected certificate produces a different
key and is reviewed again, which is correct.

---

## Stage 3 — The graph

```mermaid
flowchart TD
    START([START]) --> I[intake]
    I --> C1[completeness]
    I --> C2[compliance]
    I --> C3[quality]
    I --> C4[logistics]
    I --> C5[finance]
    C1 --> G[gather]
    C2 --> G
    C3 --> G
    C4 --> G
    C5 --> G
    G --> V[verify]
    V --> E([END])

    style C2 fill:#1e3a5f,color:#fff
    style V fill:#5f1e1e,color:#fff
```

### The fan-out is genuinely concurrent — and that took a fix

```java
// Looks parallel. Is not: node_async wraps a SYNCHRONOUS function and
// computes it eagerly on the calling thread.
builder.addNode(node, node_async(state -> runReviewer(reviewer, state)));

// Actually parallel.
AsyncNodeAction<ReviewState> action = state ->
        CompletableFuture.supplyAsync(() -> runReviewer(reviewer, state), pool);
```

Without a timing assertion this would have shipped as a "parallel" pipeline that
was nine minutes rather than two, and the architecture diagram would have been
wrong in a way nobody could see.

**Bounded at 2 concurrent calls.** Five at once into a free tier rate-limits, and
a rate-limited reviewer is a reviewer that *did not run*.

### The point worth making

> The reviewers were already isolated so one could not anchor on another's
> findings. That isolation is exactly what made them safe to run concurrently.
> **The correctness requirement paid for the performance fix.**

### `ReviewState` — and the channel that bites

Graph state is a `Map<String, Object>`; nodes return updates rather than
mutating. A returned key **replaces** by default, which is wrong when five
reviewers all return findings — the last would win and four would vanish.

```java
FINDINGS, Channels.<ReviewFinding>appenderWithDuplicate(List::of)
```

`appenderWithDuplicate`, not `appender`: the plain variant drops a value equal to
one already present, so two reviewers raising the same problem — or a node
running twice through a cycle — would be silently lost.

**`FAILURES` is a separate channel from `FINDINGS`**, so a reviewer that could
not run never looks like one that found nothing.

---

## Stage 4 — Verification, cheapest check first

```
GroundingCheck.check()            free, substring search
   │
   ├─ UNGROUNDED   → discarded and recorded. Never challenged, never shown
   ├─ UNVERIFIABLE → survives, flagged (scan — no source text)
   └─ GROUNDED     → BLOCKING or MAJOR?  → VerifierAgent.challenge()
                     MINOR or INFO?      → survives unchallenged
```

Nothing is spent challenging a finding that was never grounded.

### Grounding is the guardrail I would defend hardest

The worst thing this system can produce is **not** a missed problem. It is a
confident, well-written finding citing a certificate clause that does not exist.
A reviewer who checks two citations and finds them invented stops trusting every
finding the system will ever produce — including the correct ones.

Whitespace is normalised (PDF extraction inserts line breaks a model will not
reproduce); wording is not (a paraphrase is not a quote).

### A correction to the usual advice

Standard guidance for adversarial verification is *"default to refuted when
uncertain"*, because a false positive is usually the expensive failure.

**Inverted here.** A dropped BLOCKING finding means a non-compliant vendor ships
to stores; a surviving false positive costs a human five minutes. So the verifier
must positively demonstrate a finding is wrong, or it stands — and a verifier
that could not run says so in the reason rather than letting a reader assume the
challenge passed.

---

## Stage 5 — Conflicts, surfaced not resolved

`ConflictDetector`, no model involved:

| | When | Strength |
|---|---|---|
| `SkuDispute` | Two reviewers, same SKU, severities ≥2 apart | Strong |
| `DocumentDispute` | Two reviewers, same document, ≥2 apart | Weaker |

**A conflict is never resolved automatically.** Both positions and both pieces of
evidence go to a human, because at least one finding is wrong and deciding which
is a business judgement.

Half the detector's tests assert something is **not** a conflict. BLOCKING vs
MAJOR is two reviewers weighting the same problem slightly differently; flagging
it would be noise, and noise is what gets a review tool switched off.

---

## The security model, layered

```
1. Injection scanning        advisory — can be evaded
2. Spotlighting              advisory — can be argued around
3. Typed output              structural — AgentFinding has no `approved` field
4. Read-only database role   absolute — Postgres refuses
```

Only the last cannot be talked out of:

```sql
GRANT SELECT ON required_document, compliance_rule,
                logistics_requirement, finance_threshold  TO review_agent;
-- application, review_finding, audit_entry: never granted
-- INSERT/UPDATE/DELETE: never granted, on anything
```

**The omission is the design.** An agent cannot rewrite the rules it is judged
against, cannot read another reviewer's findings, and cannot alter its own audit
trail. Three tests assert exactly that.

### The LLM proposes; Java decides

```java
record AgentFinding(Severity severity, String problem, List<Evidence> evidence,
                    CheckType checkType, double confidence, String skuRef)
```

No `approved` field exists, so no injected text can produce one. `ReviewArea` and
`FindingSource` are stamped on **after** the model answers, so a compliance
reviewer cannot claim to be the finance reviewer.

Thresholds live in `application.yml` and are read by plain Java — **no threshold
is ever in a prompt to be argued with.**

---

## Where to look in the code

| Stage | Class | Method |
|---|---|---|
| Text out of files | `intake/TextExtractor` | `extract()` |
| SKUs out of Excel | `intake/SkuSheetParser` | `parse()` |
| Facts without a model | `intake/FactExtractor` | `extract()` |
| Idempotency | `ReviewService` | `submit()`, `idempotencyKey()` |
| The graph | `graph/ReviewGraph` | constructor, `runReviewer()` |
| Shared state | `graph/ReviewState` | `SCHEMA` |
| One reviewer | `agents/ReviewerAgent` | `review()` |
| Untrusted text | `agents/Spotlight` | `wrap()` |
| Prompt versions | `agents/PromptLibrary` | `get()` |
| Citations | `graph/GroundingCheck` | `check()` |
| Challenge | `agents/VerifierAgent` | `challenge()` |
| Disagreement | `graph/ConflictDetector` | `detect()` |
| Tools | `mcp/ReferenceDataTools` | four `@Tool` methods |
| Permissions | `db/migration/V3__agent_read_only_role.sql` | |
| Storage | `persistence/ReviewRepository` | `save()` |
