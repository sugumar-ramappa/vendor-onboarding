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
| **`ReviewOutput` has no channel for "checked, and it is fine"** | The real defect behind the 31 clean-pack findings. A reviewer can only speak through `findings`, so a pass has to be expressed as one. Same shape as the gate bug: "nobody looked" and "looked and found nothing" come out the same pipe. Section 7 |
| **`VerifierAgent` writes no `AuditEntry`** | So configurations 3 and 4 have no reconstructable critical-path latency and their column shows the reviewers' time only. It is also why the verifier ran uncached for a day: the most expensive component was instrumented last. Section 8 |
| **Grounding cannot check a reference-data citation** | A finding citing the rulebook is now sent to a human instead of deleted, which is safe but not verified. Closing it properly means putting the rulebook in state before the first verify pass, not after. Section 9 |
| **F19 not measured** | The cross-cutting fixture, predicted to make multi-agent **lose**. Deliberately still open, and now the only predicted cost of the architecture that has not been tested - the precision cost turned out not to be real, and the verifier turned out to work |
| **The verifier is the same model as the reviewers** | `gpt-oss-120b` checking `gpt-oss-120b`. It refuted 5 of 6 fabrications anyway, so the concern was overstated - but a stronger or simply different model for the check is the obvious next experiment, and the calibration harness now exists to score it |

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

## The reviewer was asked to check a number it was never shown

**A pattern, not an incident.** This is the third instance, and naming the shape
matters more than any one of them: **the prompt asks for a judgement the code does
not enable.**

```
logistics-v2   "call financeThresholds for the manual handling limit"
               -> rulebookFor(LOGISTICS) supplied only LOGISTICS_REQUIREMENTS

logistics-v2   "RULES COME FROM TOOLS - call logisticsRequirements"
               -> the measurement disables tools

logistics-v2   "are GTINs registered to the vendor's own company prefix?"
               -> ReviewContext.render() never emitted the GTIN
```

**Symptom.** None visible. A fixture whose planted defect was a GTIN outside the
vendor's GS1 prefix would be missed by every configuration, on every model,
forever - and it would look exactly like a model failure.

**Cause.** `render()` emitted the SKU code, description, case pack, case weight
and hazard flag. Not the GTIN. The GS1 certificate in the pack supplies the
prefix, so the reviewer had one half of the comparison and the other half was
never rendered.

It could only succeed by accident - when a vendor's `PRODUCT_LIST` happened to
quote its own barcodes in prose. Several fixtures do. F13's says *"See attached
schedule"*, which made its defect **impossible to detect**.

**How it was found.** Not by running it. A test written that afternoon checks
every `mustMention` phrase is either present in the pack or a form some measured
fixture has already produced. It flagged F13's `gtin`, which led to checking
whether the reviewer ever sees a GTIN. It does not.

**Fix.** `render()` now emits `| GTIN <value> |` per SKU. This changes every
rendered prompt and therefore invalidates the entire review cache - which cost
nothing on the day it was made, because the five fixtures due to be measured next
were new and had no cache.

**Why the pattern is worth carrying.** All three failures are a prompt and an
implementation drifting apart, and none of them throws. A model asked for
something it cannot do returns a confident answer built on what it does have, and
that answer is indistinguishable from a considered one. The prompts are treated
as documentation and the code as truth; nothing checks they agree.

## The fixture tests that pay for themselves

**Written 2026-08-26, found three defects on their first run.** Three tests in
`FixtureRulebookTest`, all deterministic, all free:

| Test | Found |
|---|---|
| a multi-defect fixture is pack-complete | **F20** planted a missing mandatory document, so the gate would short-circuit and its other four defects become unreachable - the F03 trap rebuilt inside the fixture meant to avoid it |
| a mustMention is producible | **F17** used `expired` where the proven form is the stem `expire`; **F13** used `gtin`, which led to the bug above |
| a clean fixture is actually clean | nothing yet - and that is the point, since a clean fixture hiding a real defect makes every false-positive number wrong |

**Why this is the highest-value test in the project.** A fixture bug is otherwise
only discovered by running it, and running one costs five model calls against a
tier allowing about forty-five a day. Two fixture bugs had already cost a
measurement window. These run in five seconds on every build.

The general point: **when verification is metered, move as much of it as possible
to something unmetered.** Everything checked here - document presence, thresholds,
weights, GTIN prefixes, lead times - is arithmetic against the same reference
tables the reviewers read. None of it needed a model, and all of it was previously
only being checked by one.

## A '*' row that cost 23 cached calls

**Symptom.** Fixing the timber bug invalidated far more cache than intended.

**Cause.** `required_document` has three conditionally-mandatory rows, and V5 gave
all three an `applies_when`. Two belong to one product category each. The third,
`QUALITY_AUDIT_REPORT`, is a `*` row - it applies to **every** category.

The gatherer was written to append a condition only when one exists, specifically
so categories with no conditional rules would render byte-identically and keep
their cache. A `*` row defeats that: the rendered `REQUIRED_DOCUMENTS` block
changed for all 16 fixtures, and since the review cache key contains the rendered
prompt, the completeness gate's cache and the single agent's cache both died -
about 23 model calls, against a free tier allowing roughly 45 a day.

**Fix.** V6 reverts that one row, leaving the two category-scoped conditions in
place. History shows both migrations rather than an edited V5.

**Why that row can wait and the other two could not.** The direction of the error
is opposite:

```
mandatory=true  + condition ignored -> demands a document that is not required
                                       -> FALSE POSITIVE, and at a gate it also
                                          suppresses four reviewers
mandatory=false + condition ignored -> never demands a document that sometimes is
                                       required -> a MISSED finding at worst
```

`QUALITY_AUDIT_REPORT` is `mandatory=false`, so it **fails safe**. It is a real
gap, it is still open, and it should be applied alongside some other change that
already invalidates the gate's cache so the re-measurement is paid for once.

**The lesson worth keeping.** The cache-preserving design was correct and I
defeated it on the first use, because I checked *how many rows are conditional*
(three) and not *how many fixtures each row reaches* (two, two, and all sixteen).
On a metered API the blast radius of a reference-data edit is a cost, and the
column that decides that radius here is `product_category`, not the one being
changed.

## The prompt that asked for a tool that was not there

**This is the entry to read.** A defect that was *invisible* on one provider and
*fatal* on the next, where the invisible version was the more dangerous one.

**Setup.** The measurement disables MCP tools and pre-resolves each reviewer's
rulebook into its prompt instead, because a tool call is a second billed request
and that halves the experiment. The reviewer prompts were never updated to match.
`logistics-v2` still opens with:

```
RULES COME FROM TOOLS, NEVER FROM MEMORY
Call logisticsRequirements with the application's delivery model.
...
Also call financeThresholds for the manual handling weight limit.
```

So the model is instructed - emphatically - to call tools that
`onboarding.model.tools-enabled=false` has removed.

**On Gemini, nothing happened.** No error, no warning. `logistics-v2` returned an
empty findings list on all nine calls it ever made:

```
prompt_version   calls  empty  findings
logistics-v2         9      9         0
```

Which reads downstream as *"logistics reviewed this vendor and found nothing
wrong"* - a clean review. It is not one. Nobody looked.

**On Groq the same prompt hard-fails.** Groq speaks the OpenAI protocol, which
sends `tool_choice: none` when no tools are attached and *enforces* it:

```
LOGISTICS failed for F02-APP: 400: Tool choice is none, but model called a tool
LOGISTICS failed for F04-APP: 400: Tool choice is none, but model called a tool
LOGISTICS failed for F05-APP: No content to map due to end-of-input
```

Three failures in the first four fixtures. The model tries to obey the prompt,
the API refuses, and `ReviewerAgent` correctly records a failure rather than an
empty list - so the graph reports a reviewer that could not check, which is the
truth.

**Why the loud version is better.** Identical root cause, and the provider that
crashed is the one that behaved well. Gemini's silence produced nine clean-looking
reviews of a vendor nobody had assessed for logistics; Groq produced three
failures that stopped the run. A defect that announces itself costs an hour. A
defect that returns a plausible empty answer costs a measurement, and there is
nothing in the output to suggest anything is wrong.

**It also settled a question this log had recorded as open.** The entry below
noted a theory - *"the reviewer refuses because it cannot call its tool"* - and
recorded it as untested, because the probe never reached logistics. Running the
same prompts against a second provider tested it by accident and confirmed it.
Two providers is a cheap way to find prompt bugs that one provider silently
absorbs, and that is worth more than the quota it was adopted for.

**Fix.** `logistics-v3`: the rulebook is described as supplied directly, and the
prompt states plainly that no tools exist, that a tool call is rejected, and that
a failed review means "could not check" rather than "found nothing". Paired with
the `FINANCE_THRESHOLDS` fix below, because they are two halves of one defect - a
prompt asking for things this configuration does not provide.

**Still open, deliberately.** The other four prompts carry the same instruction
and have not failed yet - `compliance-v2`, `finance-v2` and `completeness-v2` each
name one tool, and `logistics-v2` was the only one naming two. "Has not failed
yet" is not "is correct", and the honest fix is a reference-data preamble stating
that the rules were supplied and no tools exist, which would repair all five at
once. It is not done here because it changes the rendered prompt for every
reviewer, which invalidates the whole cache and would force configuration 1 to be
re-measured for a change unrelated to it. Sequenced after the current run.

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

**Fix, applied.** `rulebookFor(LOGISTICS)` now supplies `LOGISTICS_REQUIREMENTS`
**and** `FINANCE_THRESHOLDS`, alongside `logistics-v3`. The two were fixed in one
change deliberately: both are the same defect — a prompt asking for something this
configuration does not provide — and splitting them would need two measurements to
establish one fix.

**On the deferral note that used to be here.** This was originally postponed as
"not the root cause, sequence it third". That was the right call for the Gemini
run, where the recall gap traced to the timber row and logistics merely looked
idle. It stopped being right the moment these prompts met a provider that enforces
`tool_choice`: the same reviewer went from quietly returning nothing to failing
outright, and a failing reviewer *corrupts* configuration 2 rather than merely
weakening it. Deferring a fix is a judgement about cost against risk, and the risk
changed underneath the judgement.

**The theory recorded here as untested is now confirmed.** The original note said
a probe of `logistics-v3` on F03 cost zero requests because the gate
short-circuited first, so "the reviewer refuses because it cannot call its tool"
stayed a guess. Running the identical prompts against Groq tested it by accident:
`400: Tool choice is none, but model called a tool`. See the entry above.

Worth keeping as a lesson in its own right: **the hypothesis was right and could
not be confirmed on the provider I had**, because that provider absorbed the
failure silently. Adopting a second provider for quota reasons paid for itself in
prompt bugs found.

---

# 7. The measurement that finally answered the question

**2026-08-28.** All three configurations, same five dense fixtures, same fourteen
planted defects, one model. F17, F18 and F20 carry the defects (4, 5 and 5); F15
and F16 are clean, so **the false-positive column is a count over two packs**.

The median column is carried from the original uncached run and is the one number
here without a result file behind it — see §5.

```
#  configuration                    recall          false positives   routing   median
1  single agent, all five areas     0.71 (10/14)          6             n/a       28 s
2  gate + four agents               1.00 (14/14)         31          1.0000       88 s
3  gate + four agents + verifier    1.00 (14/14)         30          1.0000      180 s
```

**The hypothesis held, and the cost was bigger than expected.**

Recall: 14 of 14 against 10 of 14. On F17, carrying four separate defects, the
single agent found two and stopped. That is attention dilution, and it is exactly
what the dense fixtures were built to expose — the shallow set could not have
shown it, because with one defect per fixture there is nothing to dilute.

Precision: 31 false positives against 6, essentially all on the two clean packs.
**This was predicted to be zero.** See below — and see "the precision cost that
was not one", because reading the findings rather than counting them reversed the
conclusion this table supports.

## The prediction that was wrong, and why it is the useful one

`PREDICTIONS.md` said F15 and F16 would come back clean. They came back with 16
and 15 findings under configuration 2.

The prediction was half right in a way worth separating. The *gate* behaved as
predicted: the `applies_when` migration worked, the pack was not wrongly declared
incomplete, and the four reviewers **ran** — which is the whole point, because
before V5 they were short-circuited. The evidence the fix works is that there
were fifteen findings to be wrong about at all.

What was not predicted is what happens downstream of fixing it. Four specialists,
each told to look hard at one area, each find something to say about a clean
application. Nobody asked what the reviewers would do once they were finally
allowed to run.

**The lesson generalises past this project:** a fix was scoped to the failure it
was diagnosed from. The gate was blocking wrongly, so the fix made the gate stop
blocking wrongly, and the measurement of that fix was "does the gate pass". The
question never asked was what the newly-unblocked path produces.

## The precision cost that was not one

The 31 were counted before they were read. Reading them says the architecture
never lost precision at all.

```
                     FP (all)   MAJOR or above   INFO confirmations
single agent             6            6                  0
gate + four agents      31            0                 31
```

Configuration 2's 31 are entries of the form *"product liability insurance meets
the minimum required GBP 5M"* and *"case weight of 9.8 kg is below the manual
handling limit of 25 kg"*. Those are not accusations — they are the reviewer
stating what it checked and that the pack passed, which is arguably the most
useful thing it produces. Not one would stop a vendor.

The single agent's 6 are all `MAJOR` or above, four of them `BLOCKING`, and all
six are fabricated: it declared a paintbrush vendor's SKUs hazardous when the
fixture says `hazardous = false`, demanded safety data sheets for a hazard that
does not exist, then multiplied a case weight by a quantity appearing nowhere in
the pack. Six clean vendors stopped, against none.

**On the honesty of splitting a metric after seeing it.** This is the exact move
`PREDICTIONS.md` was written to prevent, so the constraint is that nothing is
replaced. `falsePositives()` still counts every clean-pack finding and still
reports 31 in the results file; `actionableFalsePositives()` is added beside it,
and `confirmatoryFindings()` is their difference. The number was decomposed, not
narrowed, and the reader can see the decomposition and recompute the original.

**What it does not excuse.** The reviewers are still saying something on a pack
where the correct output is silence, and counting it more kindly does not change
that. The defect is upstream: `ReviewOutput` gives a reviewer nowhere to say
*"I checked this and it is fine"*, so a pass has to come out through `findings`
because that is the only channel there is. The model is using the API it was
given.

That is the same bug as the gate one level up, where a skipped review was
reported indistinguishably from a clean one. Twice now, in different components,
"checked and fine" and "not checked" — or "checked and fine" and "found a
problem" — have shared an output with no way to tell them apart. **The fix is a
type change, not a prompt change.**

## The verifier refuted nothing, and it took two more measurements to learn why

**Superseded 30 Aug.** This section originally read *"the verifier does not pay
for itself"* and attributed two removals to it: a false positive on F15 and a
genuine defect on F20. **The verifier removed neither.** Both were the grounding
check - one a fabricated citation, correctly caught, and one a correct finding
wrongly deleted, which §9 covers. The verifier refuted **zero** findings across
48, in two separate runs.

That number read as a broken component and is not. Kept here as written because
the sequence is the point: a plausible conclusion, drawn from a real measurement,
about the wrong component.

**The reason is a severity threshold.** `VerifierAgent.WORTH_CHALLENGING` is
`MAJOR`, and configuration 2's false positives were *all* `INFO` confirmations -
below the line, never challenged. On the clean packs it made no calls at all:
F15 and F16 processed thirty findings in 26 milliseconds. Everything it *was*
handed - the fourteen planted defects - was true, and it correctly left all of
them standing.

**Zero was the right answer to every question it was asked.** It had never been
shown a false finding.

## The blind spot this project had already diagnosed elsewhere

`prompt-eval`, a sibling project, calibrates an LLM judge against 30 correct and
30 deliberately wrong answers, and its own notes say why: *with only correct
answers to grade, false accepts are undetectable - you cannot observe a judge
waving through a wrong answer you never showed it.*

The verifier was in exactly that position and nobody noticed for two runs. The
same mistake, in the same workspace, in two projects, three days apart.

**The fix was the same method.** `VerifierCalibrationRunner` builds a balanced
set with no labelling judgement in it: every finding on a clean pack is false by
construction, and every finding matching a planted defect is true because the
fixture author planted it. The single agent supplies the false half - it
fabricates at `MAJOR` and above, which is what makes it visible to the verifier
at all.

```
known-FALSE findings refuted:  4 of 6      <- bench, one shot, no evidence
known-TRUE  findings refuted:  0 of 7
```

**The prediction was wrong, and that is the finding.** `PREDICTIONS.md` said
under half, reasoning that `gpt-oss-120b` checking `gpt-oss-120b` shares the
blind spot that produced the claim. It refuted two thirds.

**How it refutes** is worth recording, because it is not cleverness. The product
list contains the line *"No hazardous goods. No aerosols. No liquids."* The
single agent had that document and did not read it; the verifier found it, quoted
it, and killed three claims on it. On the arithmetic fabrication it went further:
*"the product list only states that the case pack of 24 brushes weighs 3.4 kg; it
does not specify a per-unit weight"* - diagnosing the misreading, not just the
wrong number.

## Configuration 4: the same verifier, the architecture that needs it

Run 30 Aug, predicted first. Bolted onto the single agent - the one that
fabricates - the verifier takes false positives from **6 to 1**:

```
                     finds     wrongly blocks a clean vendor
1  single agent      10/14                 6
4  + verifier        10/14                 1
```

Five of six, better than the bench's four, because the pipeline gives it the
`gatherMore` cycle the bench harness did not. Three measurements of one quantity,
converging upward as the verifier gets more of what it asked for: 4/6, 4/6, 5/6.

**Recall does not move, and cannot.** `VerifierAgent` has no path that creates a
finding. Predicted as exactly 10/14 for that reason, and stated first so that any
*higher* number would read as a harness bug rather than a good result.

> Fabrication is fixable with a checker. Missing things is not. Only
> specialisation bought recall.

**Configuration 3 still does not ship**, and now for a precise reason rather than
a vague one: it matches configuration 2 on both numbers while roughly doubling
wall clock, because it insures against a failure mode the four specialists do not
have.

## VerifierAgent had no cache — fixed the same day

**Symptom.** Re-running configuration 3 to regenerate the report — with every
reviewer served from cache — still spent five minutes and hit the per-minute
token limit, then failed on two fixtures.

**Cause.** `ReviewerAgent` consults the review cache. `VerifierAgent` does not.
Every configuration-3 run pays for every verifier call again.

That is the reverse of where caching was needed. The verifier is the most
expensive component — it is multi-turn, it can loop through `gatherMore`, and it
runs after four reviewers have already produced findings — so it is the one call
that most wants a cache and is the only one without.

**Why it was deferred, and then was not.** The verifier's cache key is harder
than the reviewer's. A reviewer's key is
`(area, prompt version, model, rendered prompt)` and is fully determined before
the call. The verifier's input includes *the findings the reviewers produced*, so
the key has to cover a set of findings whose order is not guaranteed. Getting
that wrong caches a verdict against the wrong evidence, which is worse than
paying twice — and is silent, because a wrong cache hit looks exactly like a
cheap correct one.

**The reason for deferring it was wrong, and that is the interesting part.**
`challenge()` takes **one** finding and renders **one** prompt. There is no set
and no ordering. The rendered challenge prompt already carries the finding, its
evidence, any reference data a previous pass fetched, and the whole application —
so hashing it covers everything that could change the answer, and the key is the
same shape as the reviewer's: `SHA-256(prompt version, model, rendered prompt)`.

The `gatherMore` loop needed no special case either. A second pass renders the
same finding *plus* the data the first pass asked for, which is a different
string and therefore a different key — correctly, because it is a different
question.

A day was spent not doing this because the input was described in the abstract —
*"the findings the reviewers produced"* — instead of being read at the call site,
where it is one finding. The estimate was made against a mental model of the
code rather than the code.

**Fixed**: `VerifierCache`, `JdbcVerifierCache`, migration `V7__verifier_cache.sql`,
covered by `VerifierCacheTest`. That unblocks re-running configuration 3, which
the severity split needs before its columns can be filled in.

## The guard earned its keep a second time

That failed re-run produced a complete-looking configuration 3 with two fixtures
whose verifier never ran. `ResultStore` refused to overwrite the good result with
it — the guard added after the run that reported recall 13/14 for configuration 3
against 12/14 for configuration 2, which a verifier cannot do, since it only ever
removes findings.

It then exposed the next layer of the same problem. The quarantined file sat in
`incomplete/` while a good `config-3.json` sat beside it, so `writeReport()`
rendered configuration 3 as a result *and* announced configuration 3 as
discarded. Both statements were generated from real files and neither was
wrong; the reader had no way to tell which was current.

**Fix, applied.** Two symmetric rules in `ResultStore.save()`:

- a **clean** save removes any earlier quarantined file for the same
  configuration — it has been superseded
- a **failed** save for a configuration that already has a clean result is
  dropped rather than filed — a failed retry of an answered question is not a
  finding

Three tests cover the quarantine path, which previously had none.

**The shape worth remembering:** the guard was correct and its *reporting* was
not. Refusing to record a bad number is only half the job; the other half is that
the report cannot state two things that contradict each other.

---

# 8. The measurement that measured the cache

**2026-08-28, last thing.** Every document quoted the same latency row — 28 s,
88 s, 180 s — and two of those three numbers were in no result file.

**Symptom.** `config-1.json` recorded `medianWallClockMs: 8`. `config-2.json`
recorded 22. Eight and twenty-two *milliseconds*, for a review that calls a
hosted model five times.

**Cause.** Wall clock is a stopwatch around `graph.review(context)`. Both
configurations had last been regenerated from cache, so the stopwatch timed a
database lookup. The number was not wrong; it was answering a different question
from the one the column implied.

**What made it worse than a bad number.** The real figures had been transcribed
into prose by hand, so every document told a story its own data contradicted, and
the contradiction was invisible unless you opened the JSON. A measurement that
lives in a README is not a measurement — nothing re-derives it, nothing tests it,
and it survives changes that should have invalidated it.

## The durations were never lost

`ReviewerAgent` returns the cache hit's `originalLatencyMs` in its audit entry
rather than the microseconds the lookup took — deliberately, and commented as
such: *"kept so a cached run can still report honest timings"*. Every real call's
duration was still sitting in the run. Nothing read them back.

So the fix is not a re-measurement. It is a reader:

```java
static long criticalPathMs(List<AuditEntry> gate, List<AuditEntry> reviewers) {
    return gate.stream().mapToLong(AuditEntry::latencyMs).sum()
         + reviewers.stream().mapToLong(AuditEntry::latencyMs).max().orElse(0L);
}
```

**`max`, not `sum`, is the whole point.** The gate runs to completion before the
fan-out; the four reviewers then fork onto virtual threads. Summing them would
report configuration 2 as five times the baseline when it is roughly three — and
that error flatters the single agent in precisely the comparison this project
exists to make. `CriticalPathTest` pins it with four reviewers at 20/45/30/25
seconds behind a 10-second gate: 55 s, not 130 s.

## What it does not cover

`VerifierAgent` writes no `AuditEntry` at all, so configuration 3's challenge
calls are invisible to this and its figure is a floor rather than a total. That
is the same blind spot that let the most expensive component run uncached for a
day: the verifier was built as the interesting idea and instrumented last.

## The result, 29 August

Configurations 1 and 2 regenerated from cache — every call a hit, **no quota
spent** — and the reconstruction landed at:

```
                        prose said   reconstructed   wall clock (cached)
1  single agent            28 s         27.6 s            12 ms
2  gate + four agents      88 s         87.8 s            23 ms
```

**The hand-transcribed figures were right.** That is worth stating plainly,
because the problem was never that the numbers were wrong — it was that nothing
could show they were right. A correct number with no provenance and an incorrect
one are indistinguishable to a reader, and the whole point of a measured project
is that a reader does not have to take your word for it.

The wall-clock column beside it is the evidence for the diagnosis: 12 ms and
23 ms, the cache being timed, exactly as predicted.

Configuration 3 still renders `-`, and cannot be fixed by re-running: the
verifier writes no audit entry, so there is nothing to reconstruct from. That is
an instrumentation gap, not a measurement one.

**The shape worth remembering:** the number was already being collected and
already correct. What was missing was a consumer, and its absence was disguised
by a second number that looked plausible and measured something else.

---

# 9. The citation check that deleted a real defect, twice

**2026-08-29 and 30.** Configuration 3 scored 13 of 14 while configuration 2
scored 14. The missing defect had been found correctly, and was deleted on its
way out - twice, for two different reasons, both in `GroundingCheck`.

The finding was right: F20's floodlight case weighs 28.4 kg against a 25 kg
manual handling limit, delivered direct to store where there is no forklift.

## First cause: a table read as a table

The reviewer cited the product-list row it read the weight from:

```
reviewer quoted:   RAV-FLD-50 | LED floodlight 50W IP65 | GTIN 5033333000011 | ...
document says:     RAV-FLD-50  LED floodlight 50W IP65  GTIN 5033333000011  case pack 8  28.4kg
```

The model read a fixed-width table as a table and re-rendered the column
separators as pipes, then elided the tail. Every fact correct, the right
document, and `contains()` - an exact substring match after collapsing
whitespace - called it a fabrication, because a pipe is not whitespace.

**Fix.** Separator punctuation (`| │ ¦ • · tab`) normalises to space, and an
ellipsis means "and the rest": segments either side are matched in order.
Deliberately narrow - commas and full stops are *not* normalised, because
`28.4` and `284` are different weights and a clause can turn on a comma.
`CitationMatchingTest` pins both halves, and the class that matters more is the
one asserting what must keep failing: invented text, a paraphrase where every
fact is true and no words match, and `284kg` never matching `28.4kg`.

## Second cause, revealed by fixing the first: citing the rulebook

With the quote grounding, the same finding failed on its *other* citation:

```
logistics-...-1 cites unknown document RULEBOOK
```

The finding compares a vendor value against a policy limit. The 28.4 kg comes
from the pack; the 25 kg comes from the retailer's rulebook, which the reviewer's
own prompt calls authoritative and hands to it directly. `GroundingCheck` only
knows about vendor-submitted documents, so citing the authority it was told to
use was treated as fabrication.

**Every "vendor value X breaks policy limit Y" finding has this shape.** Half the
evidence is the vendor's and half is the retailer's, and only one half was
checkable.

**Fix.** A citation naming reference data is marked unverifiable and goes to a
human rather than being discarded.

## The rule both fixes share

Neither made the check more permissive about *fabrication*. F15's finding, which
cites a document that does not exist in the pack, is still discarded - correctly,
and it is the one removal in configuration 3 that should happen.

What changed is that "I could not verify this" stopped being reported as "this is
invented". That is the third instance of one bug in this codebase:

```
the gate         a skipped review reported identically to a clean one
the harness      a cached latency reported identically to a measured one
grounding        an unverifiable citation reported identically to a fabricated one
```

**Two states sharing one output, with no way to tell which.** Worth naming as a
category, because it has now cost a missed defect, a lost measurement, and a
deleted true finding - and none of the three threw an exception.

## The weakness accepted, rather than hidden

A reviewer could now evade grounding by citing "RULEBOOK" for an invented claim.
That hole is real and it is taken deliberately: the finding is not cleared, it is
marked unverifiable and shown to a person alongside the note that its source
could not be checked. The alternative was deleting correct findings outright.

Closing it properly means grounding against the reference text itself, which
needs the rulebook in state *before* the first verify pass - currently only the
`gatherMore` cycle puts it there.

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
