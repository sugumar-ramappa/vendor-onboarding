# Interview reference

Five documents. **Start with the demo script** - it is the ten minutes you would actually perform.

| # | Document | Answers |
|---|---|---|
| 0 | [**demo-script.md**](demo-script.md) | *"Show me."* Ten minutes in order, with the sentences to say. Only one step costs quota |
| 1 | [**architecture-flow.md**](architecture-flow.md) | *"Walk me through it."* Diagrams, the stack, and why multi-agent is justified rather than decorative |
| 2 | [**code-walkthrough.md**](code-walkthrough.md) | *"Show me the code."* Every class, in data-flow order, with the decision inside each |
| 3 | [**worked-example.md**](worked-example.md) | *"Show me one going through."* One real fixture end to end: the documents, each reviewer's checks, every SQL query and model call |
| 4 | [**testing.md**](testing.md) | *"How do you know it works?"* 150 tests, 14 fixtures, and what is honestly still unmeasured |

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
> role. 150 tests, no API key needed. 14 hand-written fixtures with planted
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

**Say the honest caveat before you are asked.** *The fan-out alone would not
justify a graph library — `CompletableFuture.allOf` does that in ten lines.*
Three other things in the flow are not ten lines:

| Feature | Graph primitive | Business reason |
|---|---|---|
| Incomplete pack skips the four reviewers | conditional edge | chasing documents one at a time turns two weeks into eight |
| Verifier sends a finding round again | **cycle**, bounded at 2 | resolves findings that would otherwise cost a person's time |
| Blocking review pauses for days, then resumes | **checkpointing** | the alternative is holding a request open or throwing the review away |

The third is the one that would genuinely hurt to hand-roll — it means
serialising in-flight state — and it is why every domain record implements
`Serializable`.

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

**The measurement has not run, and that was a decision.**

The Gemini free tier allows **20 model requests per day per model** - the quota
id is `GenerateRequestsPerDayPerProjectPerModel-FreeTier` and the value is
literally 20. The full experiment is around 224 requests, because a tool call is
a second request: the model pauses, the tool runs locally, and the result goes
back in a new request. Four of the five reviewers call a tool.

That is eleven days of 20-request slices to produce a recall figure over 14
fixtures. Cutting to six fixtures still takes four days and produces a number
with an error bar wider than any difference it would be measuring.

The harness, the 14 fixtures with planted defects, the matcher and the resumable
runner are all built and tested. It runs whenever quota allows, or in an hour on
any provider with a larger free tier - `ReviewModel` is an interface and the
model is a configuration value.

**The constraint did shape the design, which is the part worth saying.** The
cache is content-keyed on `(area, prompt version, model, prompt)` and lives in
Postgres, so the run resumes across days without re-paying for anything. That
was a cost decision before it was an availability one.

**And the quota running out is how I know fail-closed works.** Three times, on
real infrastructure, every rate-limited reviewer was recorded as a *failure*
rather than an empty result. The application came back `ESCALATED_INCOMPLETE`
with zero findings - because zero findings from a reviewer that never ran is not
a clean review.

A system that returned "no findings, all clear" would have approved a vendor
nobody looked at. That is not a design being described; it is one that held under
a failure nobody planned.
