# Engineering log

Problems hit while building this, what caused them, and what fixed them.

Correctness bugs and framework traps. The performance and cost work has its own
document - see [`performance-and-cost.md`](performance-and-cost.md).

Kept because the failures here were mostly **silent**. Nothing threw; the data
was quietly wrong. Those are the ones worth being able to describe.

---

# 1. Performance and cost

Moved to [`performance-and-cost.md`](performance-and-cost.md) - the latency and
token-spend story is the substantial one and deserves its own document.

Short version: the first real review took 105 seconds, which made the step-8
measurement a ~18 hour run and therefore impossible. Fixes are caching reference
lookups, running the independent reviewers concurrently, and caching whole
reviews by content hash.

# 2. Bugs the tests caught

The ones that would have been silent in production.

## The spreadsheet header row became a SKU

**Symptom:** a vendor file with a title row produced one more SKU than it should,
called `SKU`.

**Cause:** `findColumns` located the header row but returned only the column map.
Row iteration then started after row 0 rather than after the header.

**Fix:** return a `Header(rowIndex, columns)` record and iterate from
`header.rowIndex() + 1`.

**Why it matters:** silent. A junk SKU in every application whose vendor used a
template with a title row — which is most of them.

## `en62841` did not match `EN 62841`

**Symptom:** a valid certificate produced a BLOCKING finding for a missing
standard.

**Cause:** the standards regex was not case-insensitive.

**Fix:** `Pattern.CASE_INSENSITIVE`, with the captured group upper-cased so
output stays normalised.

**Why it matters:** a vendor's typesetting is not a compliance failure. This
would have rejected good vendors, and the finding would have looked entirely
credible.

## An unknown expiry reported as "not expired"

**Symptom:** would have been none — that is the problem.

**Cause:** `expiredBy()` returning a boolean means an unparseable date defaults
to `false`.

**Fix:** return `Optional<Boolean>`. Empty means "we do not know", which routes
to a human.

**Why it matters:** the canonical fail-open bug. A certificate whose date could
not be read would have passed as valid.

## A scanned certificate would have passed as clean

**Symptom:** a scan produces a valid PDF with pages and no text.

**Cause:** treating "no extractable text" as "an empty document".

**Fix:** detect it explicitly. Read it with a vision model, or reject it — never
pass it on.

**Why it matters:** completeness sees the file present, compliance finds nothing
to object to, and **the vendor appears compliant because their certificate was
unreadable.**

---

# 3. Framework traps

Each of these was a silent or misleading failure.

## Spring Boot 4 split autoconfiguration into modules

**Symptom:** clean startup, then `relation "required_document" does not exist`.

**Cause:** `flyway-core` on the classpath does nothing without
`spring-boot-flyway`. Boot 4 moved the autoconfiguration into per-technology
modules.

**Fix:** add `org.springframework.boot:spring-boot-flyway`.

**Why it matters:** nothing warns you. The migrations are on the classpath, the
app starts, and the failure appears at the first query.

## Declaring a DataSource bean disables Boot's own

**Symptom:** Flyway tried to connect as `review_agent` and failed to
authenticate.

**Cause:** `DataSourceAutoConfiguration` is `@ConditionalOnMissingBean(DataSource
.class)`. Declaring the read-only agent DataSource silently switched off the
application's primary one, so Flyway picked the only remaining candidate.

**Fix:** declare the primary DataSource explicitly, marked `@Primary`.

**Why it matters:** the error message points at authentication, which is nowhere
near the cause.

## Two JdbcTemplates, and a test silently got the wrong one

**Symptom:** a test asking for the table list got **4 tables instead of 11** and
looked like a broken migration.

**Cause:** two `JdbcTemplate` beans made `@Autowired JdbcTemplate` ambiguous, and
the read-only one won. Postgres only shows tables the current role has rights on,
so the query succeeded and returned almost nothing.

**Fix:** `@Bean(defaultCandidate = false)` on the agent beans, so they can only be
injected with an explicit qualifier.

**Why it matters:** the most misleading failure in the project. Nothing threw;
the data was just quietly incomplete. Worth knowing that a read-only role changes
what `information_schema` reports.

## Spring wraps SQL exceptions

**Symptom:** a permission test passed for the wrong reason.

**Cause:** Postgres says `permission denied for table X`, but Spring wraps it in
`BadSqlGrammarException` whose own `getMessage()` contains only the SQL.

**Fix:** walk the cause chain when asserting.

**Why it matters:** a security test that passes without testing the security
property is worse than no test.

## `Channels.appender` silently drops repeats

**Symptom:** would have been a trace that lied about which nodes ran.

**Cause:** LangGraph4j has two appender variants. The plain one discards a value
equal to one already present.

**Fix:** `appenderWithDuplicate`.

**Why it matters:** two reviewers raising the same finding, or a node running
twice because the verifier sent work back round the cycle, would vanish.

## A node before a parallel fan-out had its findings counted twice

**Symptom:** gate plus four reviewers, one finding each — and
`findings().size()` returned 6.

**Cause:** not re-execution. A print inside the gate showed it ran exactly once.
When LangGraph4j merges the parallel branches it re-applies the updates recorded
*before* the fork. Against a replace channel that is a no-op; against an
appender every pre-fork value lands twice.

**Fix:** the gate writes to `GATE_FINDINGS` / `GATE_AUDIT` / `GATE_FAILURES` /
`GATE_TRACE`, deliberately absent from `SCHEMA` so they replace.
`ReviewState.findings()` joins the two halves, so no caller knows.

**Why it matters:** nothing threw, and both copies were identical and
individually valid. The gate's finding was simply counted twice — enough to shift
every precision number in the measurement with no visible symptom. The rule it
produced covers checkpoint replay too: *a node's channel must be idempotent under
re-application unless the node sits where re-application cannot happen.*

**The test:** in a run with no cycle, no node may appear twice in the trace.

## `@ConfigurationProperties` without `@ConfigurationPropertiesScan`

**Symptom:** `required a bean of type 'PolicyProperties' that could not be found`.

**Cause:** `@SpringBootApplication` does not imply
`@ConfigurationPropertiesScan`. The record was annotated, validated, documented —
and never registered.

**Fix:** `@ConfigurationPropertiesScan` on the application class.

**Why it matters:** it fails at startup rather than compile time, so it surfaced
only when a test loaded the full context. The whole point of typed properties is
failing early with a readable message; this failure mode is one step later than
it looks.

## Resuming with an empty map re-ran the entire review

**Symptom:** `resume()` never reached the node it had paused before.

**Cause:** `invoke(Map.of(), config)` is not "continue" — an argument map is read
as the input to a *new* run, so the graph started again at the gate.

**Fix:** `invoke(GraphInput.resume(), config)`.

**Why it matters:** the failure is silent and expensive. A resume that
re-executes four reviewers still produces a correct-looking answer, just slowly
and at double the cost — the exact thing checkpointing exists to avoid. The test
asserts the stub model's call count is unchanged across the resume, which is the
only way to see it.

## The verifier could never return UNRESOLVED

**Symptom:** none. The cycle worked in tests and would never have fired in
production.

**Cause:** the prompt told the model to return UNRESOLVED, but the record it
binds to had no field for it:

```java
record Challenge(boolean disproved, String reason, List<Evidence> evidence) {}
```

Two outcomes in the binding, three in the domain. The missing one silently
became `survives`.

**Fix:** `unresolved`, `needsEvidenceFor` and `needs` added to `Challenge`, and
a `verifier-v2` prompt that describes them.

**Why it matters:** the stub in the tests returned UNRESOLVED happily, so every
cycle test passed against a path the real model could not reach. **A stub that
is more capable than the thing it stands in for tests nothing.** Found by reading
the binding record while adding something unrelated, not by a failing test - and
that is the uncomfortable part.

## Three constructors made a bean unconstructable

**Symptom:** `No default constructor found`.

**Cause:** adding test constructors to `TextExtractor` left Spring with three
candidates.

**Fix:** `@Autowired` on the intended one. Spring only auto-selects when there is
exactly one.

---

# 4. Environment and tooling

## The Maven search API serves stale versions

| | Search API said | Actually current |
|---|---|---|
| LangChain4j | 1.0.0 | **1.19.0** |
| LangGraph4j | 1.6.0-beta5 | **1.8.24 stable** |

**Fix:** read `repo1.maven.org/.../maven-metadata.xml` directly.

**Why it matters:** the whole plan was written around LangGraph4j being beta,
with hours budgeted for fighting an unstable API. It is stable, and that budget
was not needed. A stale version number changed a project estimate.

## Testcontainers 2.x renamed its modules

`postgresql` → `testcontainers-postgresql`, and Spring Boot's BOM does not manage
them, so `testcontainers-bom` has to be imported.

## Maven Wrapper instead of installing Maven

`brew install maven` wanted to upgrade `openjdk` plus six unrelated graphics
libraries to install a build tool that only needs *a* JDK. The wrapper downloads
Maven into `~/.m2` instead — nothing system-wide, and the build is pinned to one
Maven version on every machine and CI runner.

## Rancher Desktop and Testcontainers

Socket lives at `~/.rd/docker.sock`, which Testcontainers does not probe. One
line in `~/.testcontainers.properties` fixes it machine-wide. Ryuk, the cleanup
sidecar, fails under Rancher — disabled **at project level** rather than in the
user file, so other projects keep it.

## PDF rendering opened a Java app on macOS

PDFBox renders pages through AWT, which macOS treats as a GUI application and
puts in the dock. `java.awt.headless=true`, which is what a server runs with
anyway.

## Spring does not read `.env` files

Unlike python-dotenv. `spring.config.import: optional:file:./.env[.properties]`
gives the same ergonomics.

**And a self-inflicted one:** the setup instructions contained
`export GOOGLE_API_KEY=...` with `...` as a placeholder inside a copy-pasteable
block. Pasted literally, it sends `...` as the key. The error was
`API_KEY_INVALID`, which looked like a bad key rather than a bad instruction.

## The model was retired mid-build

`gemini-2.5-flash` is no longer available to new projects; the API's own error
named the replacement. Now pinned to `gemini-3.6-flash`, and deliberately **not**
`gemini-flash-latest` — an alias would silently swap the model underneath a
measurement, and a recall difference between two runs could then be the
architecture or a model change with no way to tell.

---

# 5. Still open

| | |
|---|---|
| **Review latency** | 105s. Section 1. Blocks step 8 |
| **MCP server publishes no tools** | In-process tool calling works, but the server logs `No tool methods found`. The `ToolCallbackProvider` bean is not reaching the MCP autoconfiguration |

---

# 6. Bugs the measurement caught

The measurement was built to answer "does specialisation help". Its first honest
answer was "no", and chasing that produced the most interesting defect in the
project.

## The gate that hid four reviewers

**Symptom.** Over the same seven fixtures, the multi-agent configuration was
worse on *both* headline numbers:

```
                                fixtures   recall          false positives
1  single agent, all five areas     7      1.00  (5/5)           1
2  gate + four agents               7      0.80  (4/5)           2
```

Routing accuracy was **1.0000** — every finding that was made reached the correct
reviewer. So the system was not misrouting. It was not seeing.

**The first wrong answer.** The reviewer caches showed this:

```
prompt_version   calls  empty  findings
quality-v2           9      1         8
compliance-v2        9      5         7
completeness-v2     15     10         5
finance-v2           9      8         2
logistics-v2         9      9         0     <- never fired, ever
```

`logistics-v2` had produced nothing across nine calls, and the missed defect on
F03 was a logistics defect — a vendor declaring *"Advance Ship Notice: not
currently supported"* while shipping into a distribution centre. The obvious
reading was a broken logistics prompt, and there was a plausible mechanism
sitting right there: quality is the only reviewer with no tool to call, and
quality is the only one that reliably fires.

**That reading was wrong**, and one line of run output said so:

```
F03-APP: pack incomplete (1 finding) - skipping the substantive reviews
```

The logistics reviewer never ran on F03. It was not silent; it was never asked.
Nine empty rows meant "asked about six other fixtures, whose defects belonged to
other areas" — which is the correct answer, not a broken one.

**Cause.** The completeness gate flagged `TIMBER_CHAIN_OF_CUSTODY` as missing,
`BLOCKING`, `checkType: DETERMINISTIC`, `confidence: 1.0`. Its own quoted
evidence contains the refutation:

```
TIMBER_CHAIN_OF_CUSTODY (mandatory) - Timber and timber-derived only
```

F03 sells `KES-SCR-100`, steel wood screws. Chain of custody does not apply. But
look at how the requirement is stored:

```
 product_category    | document_type            | mandatory | note
 BUILDING_MATERIALS  | TIMBER_CHAIN_OF_CUSTODY  | t         | Timber and timber-derived only
```

**`required_document` has no `applies_when` column.** `compliance_rule` does —
the gatherer renders it as *"accepts X when Y"*. For required documents the
condition is prose in a `note`, beside a boolean that says `true`
unconditionally. The reviewer read the boolean and rendered the note as
commentary, which is exactly what the schema told it to do. The model was not
hallucinating; it was correctly reporting a rulebook that says the wrong thing.

**Why one defect cost two numbers.** The gate short-circuits: an incomplete pack
skips the substantive reviews and asks the vendor for documents. So a single
false "incomplete" became:

1. a false positive, from the gate itself, and
2. a **missed seeded defect**, because the four reviewers that would have caught
   it never ran.

One data-modelling error, both halves of the score. The single-agent baseline has
no gate, cannot short-circuit, reviewed everything, and found the ASN problem.

**What this does not show.** It is not evidence against multi-agent review. The
specialisation worked where it ran — configuration 2 produced *less* noise than
the baseline on F08 and F10. What it shows is that a gate inherits the
correctness of its rulebook and then amplifies it, and that a fail-dangerous
short-circuit turns a recoverable false positive into an unrecoverable miss.

**The deeper problem is the one the code already knows about.**
`ReviewerAgent.review` is careful that a failed model call returns a recorded
failure rather than an empty list, because the caller "needs it in order to
distinguish *nobody looked* from *looked and found nothing*". The graph loses
that same distinction one level up: four reviewers that never ran are reported
indistinguishably from four reviewers that ran and were satisfied.

**Fix, not yet applied.** Deliberately, and in this order — one change per
measurement, or the next number has two possible causes:

1. Add `applies_when` to `required_document`, mirroring `compliance_rule`, and
   render it. This is the root cause.
2. Make the gate's short-circuit fail-safe: either run the substantive reviewers
   regardless and report incompleteness alongside their findings, or restrict the
   short-circuit to documents whose absence genuinely makes review impossible.
   A skipped review must never be reported as a clean one.
3. Only then the smaller logistics gap below.

Step 1 changes the rendered `REQUIRED_DOCUMENTS` block, which invalidates the
`completeness-v2` **and** `single-v1` cache rows — `single-v1` receives every
rulebook. So it forces both configurations to be re-measured, at roughly a full
day of free-tier quota. That is the honest price of the fix, and it is why the
fix is scheduled rather than sneaked in.

## The logistics reviewer is asked for data it is never given

**Symptom.** None. Found by reading, while the above was being diagnosed.

**Cause.** `logistics-v2` instructs the model to *"call financeThresholds for the
manual handling weight limit"*. The measurement pre-resolves rulebooks into the
prompt instead of exposing tools, and `rulebookFor(LOGISTICS)` supplies only
`LOGISTICS_REQUIREMENTS`. With tools enabled the reviewer fetched both; the
cost optimisation silently dropped one.

So the case-weight check has been running without the limit it compares against —
and an optimisation that was justified as *"same SQL, same rows, same prompts,
only the delivery changes"* did not in fact deliver the same rows.

**Fix, prepared but not applied.** `logistics-v3.txt` is written: it describes the
rulebook as arriving either inline or from tools, and states that returning no
findings because a tool was unavailable is the worst outcome, since it reads
downstream as "logistics found nothing wrong". The one-line gatherer change is
noted at the `case LOGISTICS` branch in `MeasurementRunner`.

Held back because it invalidates the logistics cache and forces configuration 2
to be re-measured for a change that is not the root cause. Sequenced third.

**Worth noting about the tool instruction.** A probe of `logistics-v3` on F03 was
run to test the "reviewer refuses without its tool" theory. It cost zero model
requests, because the gate short-circuited before logistics was reached — which
is how the real cause was found. The theory remains untested, and is recorded
here as untested rather than as a finding.

---

# What to take into an interview

Three of these, because they show different things:

**The gate that hid four reviewers** — the strongest one, and the only one where
a measurement did the finding. The architecture scored *worse* than its own
baseline, the first plausible explanation was wrong, and the real cause was a
conditionally-mandatory document modelled as unconditionally mandatory. It has
everything worth demonstrating: a negative result reported rather than buried, a
wrong hypothesis discarded on evidence, a defect in the data rather than in the
model, and a fail-dangerous design that turned one false positive into a missed
defect. It also has a cost — the fix forces a full re-measurement — which is why
the order of the next three changes is written down.

**The two-JdbcTemplate bug** — debugging under a misleading symptom. Nothing
threw. A test asking for the table list got 4 instead of 11, because a read-only
role changes what `information_schema` reports, and the query succeeded.

**The unknown-expiry default** — the fail-open instinct caught by design rather
than luck. `Optional<Boolean>` instead of `boolean` turns "we assumed valid" into
"we do not know, ask a human".

The performance story is in the other document and is also strong.
