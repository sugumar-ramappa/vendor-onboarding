# Performance and cost

How much this system costs to run, where that cost comes from, and what was done
about it.

The short version: **an LLM call is the most expensive thing in the pipeline by
three orders of magnitude, so most of the engineering is about not making one.**

---

# 1. The measurement that started this

First real review against Gemini, one reviewer, two documents:

```
15:20:10  tool complianceRules(POWER_TOOLS)
15:20:35  tool financeThresholds()                 +25s
15:20:44  tool requiredDocuments(...)              +9s
15:21:35  three findings returned                  +51s
                                                   ─────
                                                   105s
```

Correct findings, including the scope judgement no rule engine could make. And
unusably slow.

## Why 105 seconds is a correctness problem, not a polish problem

| | |
|---|---|
| One reviewer | 105s |
| Five reviewers, sequential | ~9 min per application |
| **30 fixtures × 4 configurations** | **~18 hours per measurement run** |

Step 8 compares single-agent against multi-agent, with and without the verifier.
At this speed that experiment cannot be run at all — so the headline number the
project exists to produce is unobtainable.

**That reframes latency.** It is not "the demo feels slow". It is "the thing that
makes this project worth showing cannot be built".

---

# 2. Where the cost actually is

Rough orders of magnitude, same machine, same review:

| Operation | Time | Relative cost |
|---|---|---|
| Regex over document text | ~0.1 ms | 1× |
| Parse a 400-row spreadsheet | ~40 ms | 400× |
| Postgres reference lookup | ~2 ms | 20× |
| PDF text extraction | ~200 ms | 2,000× |
| **One model call** | **~30,000 ms** | **300,000×** |
| **One vision page** | **~8,000 ms** | **80,000×** |

Everything else is free by comparison. A design decision that removes one model
call is worth more than every other optimisation in the codebase combined.

## Which is why several existing decisions were already cost decisions

They were justified on correctness grounds in the design, but they are all cost
decisions too:

**Deterministic extraction first.** Dates, standards and amounts are pulled out
by regex and handed to the reviewer as facts. Without that, each is a question
the model has to answer — and models are unreliable at date arithmetic anyway, so
this buys accuracy *and* removes work.

**Spreadsheets are parsed, never read by a model.** 400 SKUs with GTINs, case
packs and weights come out of Apache POI in ~40ms. Asking a model to read that
sheet would be several calls, minutes of latency, and worse data.

**Vision is capped at 15 pages.** Every scanned page is a model call. An 80-page
scanned audit report would be 80 calls; past 15, a human is genuinely cheaper.

**Rules come from tool lookups, not the prompt.** Pasting every rule for every
category into every reviewer's context would work, and would spend tokens on
rules that do not apply while burying the ones that do. A lookup returns four
rows.

**Temperature 0.1.** Not a cost decision directly, but it makes results
reproducible, which is what allows caching them at all.

---

# 3. What has to happen before anything is optimised

**Token spend is currently unmeasured.** Latency was observed from log
timestamps; nothing records prompt tokens, completion tokens, or cost per review.

That is the first task, not the last. Optimising an unmeasured cost is guesswork,
and "it feels faster" is not a claim that survives questioning.

The schema already has somewhere to put it — `audit_entry` carries
`prompt_tokens`, `completion_tokens` and `latency_ms` per call. What is missing is
the listener that fills them in, and a Micrometer counter so the numbers are
visible per reviewer rather than per run.

The questions instrumentation has to answer:

- which reviewer is the most expensive, and is it the one doing the most work?
- how much of the prompt is documents, and how much is instructions?
- what does one application cost end to end?
- how much of a measurement run is re-asking questions already answered?

---

# 4. The three fixes

In order of value per hour of work.

## a. Cache reference lookups

`complianceRules(POWER_TOOLS)` returns the same four rows every time. Across a
30-fixture evaluation it is called at least 30 times, and each call is a model
round trip rather than a 2ms database query.

Reference data changes when a compliance manager edits a rule — perhaps monthly.
Caching for the life of a run is free.

The part worth stating carefully: **the cache key must contain everything that
changes the answer, and the cache must be dropped when a migration changes the
rules.** A stale rule served across a policy change is a wrong finding that looks
right — the same failure shape as a stale prompt version, and just as hard to
spot afterwards.

## b. Run the reviewers concurrently

Five reviewers, no shared state, no ordering requirement:

```
sequential   compliance → quality → logistics → finance → completeness   ~9 min
concurrent   all five at once                                            ~2 min
```

Bounded by a fixed pool, because five simultaneous calls into a free-tier model
will rate-limit. The bound is a number to record and justify, not one to guess.

**The point worth making in an interview:** the concurrency was free because the
reviewers were *already* isolated. That isolation exists to stop one reviewer
anchoring on another's findings — a correctness requirement — and it is enforced
twice, by `ReviewContext` having nowhere to put another reviewer's output and by
the agent database role having no `SELECT` on `review_finding`.

Reviewers that shared state could not be parallelised without ordering
guarantees. One design decision, two benefits, and the second was not the reason
for it.

## c. Cache whole reviews by content

Changing one reviewer's prompt should not re-run the other four.

```
key = hash(prompt version, model name, application content)
```

Re-running a measurement after editing `compliance-v2.txt` then re-runs
compliance only. The other four are served from cache in milliseconds.

Directly borrowed from the RAG project in this workspace, where an embedding
cache turned a 43-question evaluation from minutes into seconds — and that is
what made "change one thing, measure again" affordable enough to actually do.

**This is what makes the discipline practical rather than aspirational.** One
change per measurement is easy to write in a plan and impossible to follow if
every measurement costs eighteen hours.

---

# 5. The free tier as a design constraint

No billing account, so the free tier is a hard limit rather than a budget.

That has shaped the architecture in ways worth being explicit about:

**Reviews must be cacheable**, or an evaluation run exhausts the daily quota
before it finishes.

**Failures must be distinguishable from empty results.** A rate-limited reviewer
that returned "no findings" would spend quota producing a clean review of an
application nobody looked at. `ReviewModelException` exists for this.

**Retries must know what they are retrying.** A per-minute limit clears in a
minute; a per-day limit clears at midnight. Retrying the second just burns time
to fail again — a lesson carried over from the RAG project, where the same
distinction cost a day.

**Fixtures are small and fixed.** 30 applications, not 300. The measurement has
to be repeatable within a day's quota or it will not be repeated, and an
experiment run once is an anecdote.

---

# 6. Cost decisions still open

| Decision | Trade-off |
|---|---|
| **Model per reviewer** | Completeness is a checklist and could run on a cheaper model; scope judgement needs the better one. Mixing models means the measurement has to control for it |
| **Prompt size** | Instructions are ~600 tokens per reviewer, sent every call. A shorter prompt is cheaper and probably worse — worth measuring rather than assuming |
| **Document truncation** | A 40-page audit report is mostly boilerplate. Sending only relevant sections needs retrieval, which is a whole subsystem to get wrong |
| **Verifier scope** | Currently every BLOCKING and MAJOR finding is challenged. Challenging only BLOCKING would halve verifier cost and lose some false-positive reduction |

Each of these is measurable once instrumentation exists, and guesswork until
then.

---

# What to say in an interview

> "The first real review took 105 seconds. Five reviewers over thirty fixtures
> and four configurations would have been eighteen hours — which meant the
> experiment the project exists to run was impossible, so latency was a
> correctness problem rather than a polish one.
>
> Before optimising I instrumented it, because token spend was unmeasured and
> 'it feels faster' is not a result. Then three fixes: cache the reference
> lookups, run the independent reviewers concurrently, and cache whole reviews
> keyed on prompt version, model and content hash — so changing one prompt
> re-runs one reviewer instead of five.
>
> The concurrency was free. The reviewers were already isolated so that one
> could not anchor on another's findings, and that isolation is what made them
> safe to run in parallel. The correctness requirement paid for the performance
> fix."

The general principle underneath all of it:

> **An LLM call costs roughly 300,000× a regex. Most of the engineering in a
> system like this is deciding what never needs to reach a model.**
