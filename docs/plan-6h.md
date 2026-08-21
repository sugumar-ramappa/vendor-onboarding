# Six-hour plan

Non-negotiable: **Spring Boot · LangChain4j · LangGraph4j · multi-agent ·
guardrails.** Everything else flexes.

Full design in [`design.md`](design.md) — this is the subset that fits six hours
and still produces a result worth showing.

---

## The one structural change

**Spike LangGraph4j first, before any domain code.**

It is the only genuinely unknown component: 1.8.24, thin documentation, and
new to you. Everything else — Spring Boot, Maven, records, JUnit — is muscle
memory.

If the library is awkward, you find out in hour 1 with nothing invested, not in
hour 4 with a half-built domain model depending on it.

Start from the `langgraph4j-howtos` module in the project's own repo. Its
examples are the real documentation.

---

## What is cut, and why it is safe

| Cut | Saves | Reasoning |
|---|---|---|
| MCP server | 2.5h | Not in your original requirement — it was my addition. Plain LangChain4j `@Tool` methods show the same pattern |
| Postgres | 1.0h | H2 in-memory. No Docker, no Flyway, no schema work |
| PDF documents | 1.0h | Fixtures are plain text and JSON. PDFBox adds nothing to the argument |
| 5 agents → **3** | 1.0h | Compliance, Quality, Logistics. Three is enough to conflict |
| Verifier loop-back | 0.5h | Single-pass refutation instead of a bounded cycle |
| REST API | 1.0h | Driven from tests and a CLI runner |
| Observability, audit API, human-in-loop | 2.0h | Packaging, not substance |

**Kept, because they are the project:** multi-agent with isolated contexts,
LangGraph4j orchestration, conflict detection, adversarial verification, four
real guardrails, and the measurement.

---

## Hour by hour

### Hour 1 · LangGraph4j spike — no domain, no LLM

Prove you can build and run a graph:

```java
// two nodes, shared state, one conditional edge
var graph = new StateGraph<>(ReviewState.SCHEMA, ReviewState::new)
    .addNode("a", node_async(s -> Map.of("log", "a ran")))
    .addNode("b", node_async(s -> Map.of("log", "b ran")))
    .addEdge(START, "a")
    .addConditionalEdges("a", edge_async(s -> s.needsB() ? "b" : "end"),
                         Map.of("b", "b", "end", END))
    .addEdge("b", END)
    .compile();
```

**Exit criterion:** a JUnit test that runs the graph and asserts on final state.

**Checkpoint at 1h15:** if this is not working, switch to plain Java
orchestration and revisit LangGraph4j after the deadline. Do not spend hour 2 on
it.

### Hour 2 · Spring Boot + LangChain4j + one working agent

```java
interface ComplianceReviewer {
    @SystemMessage("You review supplier certificates against the rules provided...")
    ReviewFinding review(@UserMessage String pack);
}
```

Typed return, not a String — that is guardrail **G1** and it costs nothing.

**Exit criterion:** one agent returns a populated `ReviewFinding` from a real
Gemini call.

### Hour 3 · Three agents as graph nodes

Compliance · Quality · Logistics, each its own node, **each with its own
context**. No node sees another's findings — that isolation is the entire
multi-agent argument, and it is one line of discipline, not a feature to build.

Rules live in `application.yml`, injected into each prompt. Not recalled by the
model.

**Exit criterion:** one application in, three findings out.

### Hour 4 · Conflict detection + verifier

**Conflict detection is plain Java** — compare findings across dimensions:

```java
if (logistics.severity() == READY && quality.severity() == BLOCKING) {
    conflicts.add(new ReadinessDispute(logistics, quality));
}
```

**Verifier is one graph node**, one call per BLOCKING/MAJOR finding, instructed
to refute and to default to refuted when uncertain.

**Exit criterion:** a run that surfaces a conflict and kills at least one finding.

### Hour 5 · Fixtures and first measurement

12 applications as plain JSON, with seeded defects recorded:

```
02  expired GFSI certificate           BLOCKING  compliance
05  insurance territory excludes UK    BLOCKING  compliance
07  logistics ready vs cert expired    CONFLICT
09  clean pack                         (no findings expected)
11  injection payload in a field       (must not change any finding)
```

Include **clean packs** — false positives are what kill adoption, and you cannot
measure them without negatives.

**Exit criterion:** a printed table of recall and false positives.

### Hour 6 · Baseline, guardrails, README

**The baseline first, it is the differentiator:** one agent, one prompt, all
three dimensions, same fixtures.

Then the three remaining cheap guardrails:

| | Guardrail | Cost | OWASP |
|---|---|---|---|
| G1 | Typed output — `ReviewFinding` has no `approved` field | free, done in hour 2 | LLM05 |
| G2 | Spotlighting — vendor text wrapped and marked as data | 10 min | LLM01 |
| G3 | Injection scan — normalise + regex, quarantine on hit | 20 min | LLM01 |
| G4 | Grounding — every finding must quote the source | 20 min | LLM09 |

**Exit criterion:** a README that opens with the comparison table.

---

## What you will be able to say

> "Three independent reviewers orchestrated with LangGraph4j. A single agent
> covering all three dimensions found 7 of 15 seeded defects; three isolated
> agents found 12 — the single agent anchored on the first blocking finding and
> under-reported afterwards. An adversarial verifier cut false positives from N
> to M. It also surfaces where two reviewers contradict each other, which a
> single agent averages into a paragraph. Four guardrails mapped to OWASP LLM01,
> LLM05 and LLM09, each with a test."

Every clause is checkable, and it comes from six hours.

---

## Honest assessment

Six hours is **tight but achievable**, with two conditions:

1. **The hour-1 spike must work.** It is the only real risk, which is why it goes
   first.
2. **Hour 6 is not optional.** Without the single-agent baseline you have a demo;
   with it you have a result. If you are running late, cut a guardrail, not the
   baseline.

**If you can find two more hours**, spend them on: Postgres instead of H2 (30
min), the MCP tool layer (1h), and a REST endpoint (30 min) — in that order.
That takes it from "good core" to "complete".

---

## Before hour 1

```bash
brew install maven
echo 'export JAVA_HOME=$(/usr/libexec/java_home -v 21)' >> ~/.zshrc
source ~/.zshrc
mvn -version        # must report Java 21
```

Java 21 (Corretto) is already installed and is the default.
