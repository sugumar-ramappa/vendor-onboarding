# Interview reference

Three documents, in the order you would walk someone through the project.

| # | Document | Answers |
|---|---|---|
| 1 | [**architecture-flow.md**](architecture-flow.md) | *"Walk me through it."* Diagrams, the stack, and why multi-agent is justified rather than decorative |
| 2 | [**code-walkthrough.md**](code-walkthrough.md) | *"Show me the code."* Every class, in data-flow order, with the decision inside each |
| 3 | [**testing.md**](testing.md) | *"How do you know it works?"* 121 tests, 14 fixtures, and what is honestly still unmeasured |

Also worth having open:

| | |
|---|---|
| [`../performance-and-cost.md`](../performance-and-cost.md) | Where the cost is: 105s per review, caching, concurrency |
| [`../engineering-log.md`](../engineering-log.md) | Silent bugs and framework traps, with causes |
| [`../production-standards.md`](../production-standards.md) | Fail-closed, prompt versioning, idempotency, typed errors |

---

## The 60-second version

> A vendor applies to supply a retailer. Five departments review the same pack
> today — compliance, quality, logistics, finance, governance — independently,
> against different rulebooks, over several weeks.
>
> This runs those five reviews concurrently as isolated agents, reports where
> they **contradict each other**, and has an adversarial verifier try to refute
> every serious finding before a human sees it.
>
> Spring Boot 4, Spring AI, LangGraph4j, MCP tools over a read-only Postgres
> role. 121 tests, no API key needed. 14 hand-written fixtures with planted
> defects, measured across three configurations.

---

## Answer to the question that decides the interview

**"Couldn't one prompt do all of this?"**

Three of the four conditions that justify multi-agent hold here:

**It is already multi-party.** Five real departments do this today. The agents
model reviewers who exist — nothing was invented to justify the architecture.

**The conclusions genuinely conflict.** Logistics says ready to onboard; quality
says the certificate expired; compliance says the insurance excludes our
territory. Three valid positions, one decision. **A single agent averages that
into a paragraph and the trade-off disappears.**

**Independence has to be enforced.** A context that sees the expired certificate
first colours every later judgement. Prevented twice — by the type having nowhere
to store another reviewer's findings, and by the database role that cannot read
`review_finding`.

And the honest caveat: *the fan-out alone would not justify a graph library.
`CompletableFuture.allOf` does that in ten lines. What justifies it is the
verifier's cycle and checkpointing so a review can pause for a human and resume.*

---

## Four things to have ready

**The security layers, and which one actually holds.**

```
injection scanning        advisory — can be evaded
spotlighting              advisory — can be argued around
typed output              structural — no `approved` field exists
read-only database role   absolute — Postgres refuses
```

Only the last cannot be talked out of. That distinction is the most senior thing
in the project.

**The correctness requirement that paid for the performance fix.** The reviewers
were isolated to prevent anchoring — a correctness decision. Isolated things are
safe to run concurrently, so the same decision turned nine minutes into two.

**The inverted default on adversarial verification.** Standard advice is *default
to refuted when uncertain*, because false positives are usually the expensive
failure. Here a dropped BLOCKING finding means a non-compliant vendor ships to
stores, and a surviving false positive costs a human five minutes. **The
asymmetry runs the other way**, so an uncertain challenge leaves the finding
standing.

**The bug the timing assertion caught.** `node_async` wraps a synchronous
function and computes it eagerly — the nodes were parallel in the graph and
sequential in execution. Without the test it would have shipped, and the
architecture diagram would have been wrong in a way nobody could see.

---

## What is not finished, and say so first

The measurement has not run. The free-tier daily quota was exhausted during
development, so the three configurations have not been compared and there are no
headline numbers yet.

The harness, the fixtures and the matcher are built and tested. The run is
~115 model calls and one day's quota.

**Volunteering that is a better position than being asked.** And the reason it
ran out is itself the demonstration: every rate-limited reviewer was recorded as
a *failure*, the application was marked `INCOMPLETE`, and nothing was
auto-decided. A system that returned "no findings, all clear" would have approved
a vendor nobody reviewed.
