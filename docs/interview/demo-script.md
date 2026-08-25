# Demo script

Ten minutes, in order. Every step except step 4 costs **zero API quota** and works
with no network.

The point of the ordering: show the guardrail that cannot be argued with first,
because it is the most senior thing in the project and it takes thirty seconds.

---

## 1 — The guardrail that actually holds · 30 seconds · no quota

Connect to Postgres as the agents' own role and try to read the findings table.

```sql
-- as review_agent, the role the reviewers connect as
SELECT * FROM review_finding;
```

```
ERROR: permission denied for table review_finding
```

**Say this:**

> Four layers protect this system. Injection scanning can be evaded.
> Spotlighting can be argued around. A typed output schema with no `approved`
> field is structural — the model has nowhere to put an approval. But only one
> layer cannot be talked out of, and that's the one you just saw. The reviewers
> connect as a role with `SELECT` on four reference tables and nothing else.
>
> That's what enforces reviewer independence. It isn't that we ask them not to
> look at each other's findings — Postgres refuses.

Then show what it *can* read:

```sql
SELECT * FROM compliance_rule WHERE product_category = 'POWER_TOOLS';
```

```
ELECTRICAL_SAFETY    {EN 62841,IEC 62841,EN 60745}
BATTERY_COMPLIANCE   {IEC 62133,UN 38.3}   when LITHIUM_BATTERY_PRESENT
```

> The rules live in the database, not in the prompts. A compliance manager adds
> a row and behaviour changes with no code and no prompt edit — and a model can
> never recall a requirement from training data, which would be undated and
> occasionally invented.

---

## 2 — The tests · 40 seconds · no quota, no API key

```bash
./mvnw test
```

```
Tests run: 150, Failures: 0, Errors: 0, Skipped: 0
```

**Say this:**

> 150 tests, no API key, no network. Every model call is stubbed, and the
> database tests use Testcontainers, so this runs on your machine as-is.

Then open one test and show what it asserts:

```java
assertTrue(peak.get() > 1, "reviewers ran one at a time - the fan-out is not parallel");
assertTrue(elapsed < 5 * 120, "elapsed " + elapsed + "ms is close to the sequential total");
```

> This one caught a real bug. `node_async` in LangGraph4j wraps a *synchronous*
> function and computes it eagerly on the calling thread, so the reviewers were
> parallel in the graph and sequential in execution. Nine minutes instead of
> two. Nothing failed, nothing logged — the only way to see it was to assert on
> the clock.

---

## 3 — Why a graph, not `CompletableFuture` · 2 minutes · no quota

Open [`architecture-flow.md`](architecture-flow.md) at the graph diagram.

**Concede the obvious objection before it's raised:**

> The fan-out doesn't justify a graph library. Four concurrent calls is
> `CompletableFuture.allOf` in ten lines. Three other things aren't:

| Feature | Primitive | Business reason |
|---|---|---|
| Incomplete pack skips the four reviewers | conditional edge | chasing documents one at a time turns two weeks into eight |
| Verifier sends a finding round again | **cycle**, bounded at 2 | a SQL lookup settles what would otherwise cost a person ten minutes |
| Blocking review pauses for days, then resumes | **checkpointing** | the alternative is holding a request open or discarding the review |

> The third is the one that would genuinely hurt to hand-roll — it means
> serialising in-flight state — and it's why every domain record implements
> `Serializable`.

---

## 4 — One real review · 3 minutes · ~11 requests

The only step that spends quota. Free tier is 20 requests per day, so this fits
once daily.

```bash
caffeinate -i ./mvnw spring-boot:run -Dspring-boot.run.profiles=tryreview
```

The application is deliberately awkward: a certificate that is valid, in date,
and cites an accepted standard — and still wrong, because its scope reads *"hand
tools and non-powered garden implements"* while the SKU is an 18V cordless
drill. Insurance below threshold. An EDI form admitting no ASN capability. And an
injected instruction in the certificate text.

**Watch the log for the tools being called:**

```
tool requiredDocuments(POWER_TOOLS, DISTRIBUTION_CENTRE)   51ms
tool complianceRules(POWER_TOOLS)                          46ms
```

> The model paused mid-generation, asked the database a question, and continued
> with the answer. Note the timing — the SQL is sub-100ms. The thirty seconds
> around it is the model. That ratio is why everything that can be a regex is a
> regex.

**Then show the rows:**

```sql
SELECT area, severity, problem, sku_ref FROM review_finding
WHERE application_id = 'TRY-001';

SELECT document_id, page, quote FROM finding_evidence;
```

> Every finding carries a verbatim quote, and the quote is checked against the
> document before anyone sees it. A finding whose citation isn't in its source is
> discarded for free — no model call spent arguing about something invented. One
> fabricated citation destroys trust in every finding, including the correct
> ones.

**Run it a second time.** Zero model calls — the idempotency key matches on
content, so an unchanged pack is recognised even if the caller forgot to send a
key.

---

## 5 — The bugs · 3 minutes · the part that separates you

Open [`engineering-log.md`](../engineering-log.md). Pick two. All of them share a
shape: **nothing threw, nothing logged, every component was individually
correct.**

**The appender channel that double-counted.** Adding a completeness gate before
the parallel fan-out made `findings().size()` return 6 for five reviewers. The
gate ran once — a print proved it. LangGraph4j re-applies pre-fork updates when
it merges parallel branches, which is a no-op against a replace channel and a
duplication against an appender.

> Both copies were identical and individually valid. The gate's finding was
> simply counted twice, which would have shifted every precision number in the
> measurement invisibly. The rule it produced covers checkpoint replay too: a
> node's channel must be idempotent under re-application unless it sits where
> re-application cannot happen.

**The verdict the model could never return.** The verifier prompt instructed the
model to return UNRESOLVED. The record it binds to had two fields — `disproved`
and `reason`. There was nowhere to put it, so it silently became "survives".

> Every cycle test passed against a path the real model could not reach, because
> my stub was more capable than the thing it stood in for. Found by reading the
> binding record while doing something unrelated, not by a failing test — and
> that's the uncomfortable part.

**The harness that rigged its own experiment.** The fixture matcher required an
exact `ReviewArea`. The single-agent baseline stamps every finding with whichever
area slot it was constructed with, so six of eleven planted defects could never
match — capping the baseline at 0.45 recall by construction.

> Multi-agent would have won automatically and the number would have measured
> the harness. Detection and routing are now separate figures.

---

## 6 — What isn't done, said first · 1 minute

> The measurement hasn't run. The free tier allows **20 model requests per day**
> and the full experiment is around 224 — the harness, the 14 fixtures with
> planted defects, and the matcher are all built and tested, but I chose not to
> spend eleven days producing a six-fixture recall figure I wouldn't quote.
>
> The constraint did shape the design: the cache is content-keyed on
> `(area, prompt version, model, prompt)` and lives in Postgres, so the run is
> resumable in 20-request slices. That was a cost decision before it was an
> availability one.

**And the thing worth ending on:**

> The quota running out mid-run is how I know fail-closed works. Every
> rate-limited reviewer was recorded as a *failure*, not as an empty result. The
> application came back `ESCALATED_INCOMPLETE` with zero findings — because
> zero findings from a reviewer that never ran is not a clean review. A system
> that returned "no findings, all clear" would have approved a vendor nobody
> looked at.
>
> That happened three times on real infrastructure. It isn't a design I'm
> describing; it's one I watched hold.

---

## The four sentences to have ready

**On multi-agent:** *Five departments review this pack today, against different
rulebooks, and they contradict each other. A single agent averages three valid
positions into a paragraph and the trade-off disappears.*

**On security:** *Injection scanning is advisory, spotlighting is advisory, the
typed schema is structural, the database role is absolute. Only the last cannot
be talked out of.*

**On the decision gate:** *No threshold is ever in a prompt, so no document can
argue with one. A model reports; Java decides.*

**On adversarial verification:** *Standard advice is to default to refuted when
uncertain, because false positives are usually the expensive failure. Here a
dropped BLOCKING finding ships a non-compliant vendor to stores and a surviving
false positive costs a human five minutes. The asymmetry runs the other way, so
an uncertain challenge leaves the finding standing.*
