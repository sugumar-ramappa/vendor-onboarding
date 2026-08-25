# One application, end to end

Fixture `F01-scope-mismatch.json`, traced through every stage — what the vendor
sent, what each reviewer looks for, which SQL runs, and where each model call
happens.

The defect planted in this fixture is deliberately the kind **no rule engine can
catch**: the certificate is valid, in date, and cites an accepted standard. It is
only wrong because of what it *covers*.

---

## How it gets in

```bash
curl -X POST http://localhost:8080/applications \
  -F 'submission={"applicationId":"APP-2026-0113","vendorName":"Acme Tools Ltd",
       "category":"POWER_TOOLS","deliveryModel":"DISTRIBUTION_CENTRE",
       "requestedGoLive":"2026-12-01T00:00:00Z",
       "documents":[{"filename":"elec-cert.pdf","type":"ELECTRICAL_SAFETY_CERTIFICATE"},
                    {"filename":"insurance.pdf","type":"INSURANCE_CERTIFICATE"}]};type=application/json' \
  -F 'files=@elec-cert.pdf' \
  -F 'files=@insurance.pdf' \
  -F 'skuSheet=@skus.xlsx'
```

**Each document's type is declared, not guessed from the filename.** Guessing
works until a vendor names theirs `ACME_2026_final_v3.pdf`, and then it is
silently wrong: the file is stored as the wrong type, the completeness gate
reports the insurance certificate missing, and the vendor is asked for a document
sitting in the request.

`IntakeService` reconciles both directions — a declaration with no file is
rejected, and **a file nobody declared is rejected too**, naming it.

*In the fixtures the text is inline JSON, so the measurement can run without
carrying binary files in the repository. The path above is what a real
submission takes.*

---

## What the vendor submitted

**Acme Tools Ltd** — category `POWER_TOOLS`, delivery `DISTRIBUTION_CENTRE`,
go-live 1 December 2026.

### The SKU list — one line

| Vendor SKU | Description | GTIN | Case pack | Case weight | Hazardous |
|---|---|---|---|---|---|
| `ACM-DRL-18V` | 18V cordless drill, 2Ah battery, 2-pack | 5012345678900 | 6 | 12.4 kg | no |

In production this arrives as an `.xlsx` and is parsed by `SkuSheetParser`. Every
column here is later checked against something.

### The nine documents

| Document | What it says (abridged) |
|---|---|
| `company-profile.pdf` | Company number 08812345, trading since 2011 |
| `product-list.pdf` | The drill, GTIN, case pack 6 |
| **`elec-cert.pdf`** | **Scope: "Hand tools and non-powered garden implements"**, tested to EN 62841, valid to 12 April 2028 |
| `conformity.pdf` | Declaration of conformity |
| `insurance.pdf` | Policy PL-4471029, public + product liability GBP 10m, **territorial limits: United Kingdom**, 2026–2028 |
| `accounts.pdf` | Turnover GBP 14.2m, net assets GBP 3.1m, filed on time |
| `edi-form.pdf` | EDIFACT DESADV 24 hours before despatch |
| `gs1.pdf` | GS1 UK, **company prefix 5012345**, valid to 2028 |
| `audit.pdf` | Grade A, no non-conformances, audited 14 May 2026 |

Read that certificate line again. It is a **cordless drill** — a powered tool.
The certificate covers *hand tools and non-powered garden implements*.

---

## Stage 0 — Intake · **no model call**

```
elec-cert.pdf → TextExtractor.extract()   → text + page numbers + ExtractionSource
skus.xlsx     → SkuSheetParser.parse()    → List<Sku>
      all text→ FactExtractor.extract()   → DocumentFacts
```

`TextExtractor` sniffs the **magic bytes**, not the extension — a spreadsheet
renamed to `.pdf` is far more likely carelessness than an attack, but either way
the content decides. An encrypted PDF is refused rather than opened with an empty
password: a document we had to work around to read is one a person should look
at.

`FactExtractor` is regex only, and it pulls out:

```
expiry dates:  12 April 2028, 31 December 2028, 31 December 2028
standards:     EN 62841
amounts:       GBP 10,000,000 (×2)
```

**Why this matters:** every one of those is a question no model has to answer.
A regex is ~0.1 ms; a model call is ~30 seconds. It also buys accuracy — models
are unreliable at date arithmetic, and "is 12 April 2028 more than 30 days after
1 December 2026" is arithmetic.

The expiry check is *already done* by the time a reviewer sees it. The prompts
say so explicitly: *"Do not recompute dates or compare them yourself."*

---

## Stage 1 — The completeness gate · **model call 1**

One reviewer, running alone, before the other four.

### The SQL it runs

The gate has MCP tools. Mid-call, the model decides to call one, Spring AI
executes it against the **read-only** connection, and feeds the rows back:

```sql
-- ReferenceDataTools.requiredDocuments("POWER_TOOLS", "DISTRIBUTION_CENTRE")
SELECT document_type, mandatory, note FROM required_document
WHERE (product_category = 'POWER_TOOLS' OR product_category = '*')
  AND (delivery_model  = 'DISTRIBUTION_CENTRE' OR delivery_model IS NULL)
```

Which returns, for this application:

```
COMPANY_PROFILE                 mandatory
PRODUCT_LIST                    mandatory
INSURANCE_CERTIFICATE           mandatory
FINANCIAL_STATEMENTS            mandatory
ELECTRICAL_SAFETY_CERTIFICATE   mandatory   ← because POWER_TOOLS
DECLARATION_OF_CONFORMITY       mandatory   ← because POWER_TOOLS
EDI_CAPABILITY_FORM             mandatory   ← because DISTRIBUTION_CENTRE
GS1_REGISTRATION                mandatory   ← because DISTRIBUTION_CENTRE
QUALITY_AUDIT_REPORT            optional
```

**The rules are not in the prompt.** A compliance manager adds a row and the
behaviour changes with no code and no prompt edit. It also means a model can
never "remember" a requirement from training data — undated and occasionally
invented.

### The decision

All nine present → **no BLOCKING finding** → the conditional edge routes to
`fanOut`.

> Had two documents been missing, the graph would have gone to
> `requestDocuments` and **stopped**. The other four reviewers never run. That
> saves four model calls, and more importantly it asks for *everything* missing
> at once rather than discovering gaps one review at a time.

---

## Stage 2 — Four reviewers, concurrently · **model calls 2–5**

They run at the same time on virtual threads, bounded at 2 concurrent by a
semaphore. **None of them can see another's findings** — `ReviewContext` has
nowhere to put them, and the agents' database role has no `SELECT` on
`review_finding`.

### COMPLIANCE

Calls its tool first:

```sql
-- complianceRules("POWER_TOOLS")
ELECTRICAL_SAFETY    accepts ['EN 62841','IEC 62841','EN 60745']
BATTERY_COMPLIANCE   accepts ['IEC 62133','UN 38.3']  when LITHIUM_BATTERY_PRESENT
```

Then checks:

| Check | Result |
|---|---|
| Is the cited standard accepted? | EN 62841 **is** in the list ✅ |
| Is the certificate in date? | valid to 2028, go-live 2026 ✅ *(already computed by FactExtractor)* |
| **Does the scope cover the SKUs?** | **"hand tools and non-powered garden implements" vs an 18V cordless drill** ❌ |

**This is the judgement that is actually the model's.** The prompt says so:

> *"Most compliance checks are arithmetic and have already been done for you.
> The one that needs judgement is SCOPE: does what the certificate covers include
> what the vendor is applying to supply?"*

It raises:

```
severity:  MAJOR
skuRef:    ACM-DRL-18V
problem:   "The electrical safety certificate's scope does not cover a powered tool"
evidence:  elec-cert.pdf p1 — "Hand tools and non-powered garden implements"
```

That quote is **verbatim from the document**, which matters in a moment.

### QUALITY

Reads `audit.pdf`: Grade A, no non-conformances, audited May 2026. Checks
certificate expiry dates against go-live. **Finds nothing.**

### LOGISTICS

```sql
-- logisticsRequirements("DISTRIBUTION_CENTRE")
asn_required = true, asn_lead_hours = 4, gs1_registration_required = true,
pallet_labelling = true, max_lead_time_days = 14
```

| Check | Result |
|---|---|
| ASN capability? | EDIFACT DESADV at 24 hours ≥ 4 required ✅ |
| GS1 registered? | yes, prefix 5012345 ✅ |
| **Does the GTIN start with the GS1 prefix?** | `5012345`678900 ✅ |

*(Fixture F13 plants a mismatch here — a GTIN whose prefix belongs to someone
else. That is a real problem: scan it in a DC and it resolves to another
company's product.)*

### FINANCE

```sql
-- financeThresholds()
MIN_PUBLIC_LIABILITY       5,000,000 GBP
MIN_PRODUCT_LIABILITY      5,000,000 GBP
MIN_TRADING_YEARS                  2 YEARS
MANUAL_HANDLING_LIMIT_KG          25 KG
```

| Check | Result |
|---|---|
| Public liability ≥ 5m? | 10m ✅ |
| Territory covers where we sell? | "United Kingdom" ✅ |
| Trading ≥ 2 years? | since 2011 ✅ |
| Case weight ≤ 25 kg? | 12.4 kg ✅ |

*(F06 plants cover of 2m; F07 plants "European Union only"; F09 plants a 31 kg
case — a manual handling injury waiting to happen.)*

**Running total: 5 model calls.** Four returned nothing; one found the defect.

---

## Stage 3 — Gather and detect conflicts · **no model call**

`ConflictDetector` runs in plain Java. It looks for two reviewers taking
materially different positions on the same subject — a gap of 2+ severity levels
on the same SKU or document.

Here there is one finding, so no conflict.

> This is the first moment all four outputs exist together, and therefore the
> only place a conflict *can* be seen. It is also why it is a separate node from
> `verify` and why it runs **once** — re-running it would re-detect the same
> disagreement between the same reviewers.

---

## Stage 4 — Verify · **model call 6**

A funnel. Only the last step costs anything.

### Grounding first — free

```
Is "Hand tools and non-powered garden implements" actually in elec-cert.pdf?
```

Plain substring search, whitespace normalised. **Yes.** The finding proceeds.

Had the model invented or paraphrased that quote, the finding would be
**discarded here for zero cost** — no model call spent arguing about something
that does not exist. A fabricated citation destroys trust in every finding,
including the correct ones.

### Then the challenge

A *different* agent, its own prompt, told to **refute** the finding. A model
asked to check its own work agrees with itself.

It reads the certificate scope, reads the SKU description, and concludes it
cannot be refuted:

```
disproved: false
reason:    "A cordless drill is a powered tool. The scope names hand tools and
            non-powered implements, which excludes it."
```

The finding **survives**.

### If it had been unable to decide

Suppose the verifier did not know whether EN 62841 was acceptable. It would
return:

```
unresolved:       true
needsEvidenceFor: "which standards are accepted for POWER_TOOLS"
needs:            [COMPLIANCE_RULES]        ← from a closed enum
```

Then `gatherMore` runs **one** query — not four:

```sql
-- because needs = [COMPLIANCE_RULES]
SELECT requirement_code, accepted_standards FROM compliance_rule
WHERE product_category = 'POWER_TOOLS'
```

and `verify` runs again with that text at the top of its prompt, above the
vendor's documents. **Second pass re-challenges only the unresolved finding**,
not all of them.

If `needs` came back empty — nothing in the rulebook could settle it — the graph
does not loop at all. There is nothing to fetch, so a person decides.

---

## Stage 5 — Decide · **no model call, plain Java**

```java
boolean blocking   = surviving.stream().anyMatch(f -> policy.blocks(f.severity()));
boolean incomplete = !state.allReviewersRan();
boolean conflicted = !state.conflicts().isEmpty();
```

For F01: the finding is MAJOR. It does not **block** — but it does **escalate**.

```
verdict: ESCALATED   →   PAUSE before humanReview, state checkpointed
```

### Two thresholds, and the bug that came from having one

This originally used a single threshold. `blocking-severity: BLOCKING` decided
both *"does this stop onboarding?"* and *"does a person need to see it?"* — so a
MAJOR finding that had survived an adversarial challenge was **auto-cleared and
shown to nobody**.

Which contradicted the domain model. `Severity.MAJOR` documents itself as
*"needs resolution before onboarding, but a human may waive it"* — and waiving
requires being shown.

```yaml
blocking-severity: BLOCKING     # stops onboarding
escalation-severity: MAJOR      # needs a person, blocking or not
```

The gap between the two is the band a **human** decides about rather than a
threshold deciding for them. A constructor check refuses any configuration where
escalation sits above blocking, because that would let a finding stop onboarding
without anyone seeing it.

Two tests pin the boundary from both sides: a surviving MAJOR escalates, and a
surviving MINOR still auto-clears — otherwise the change is just "escalate
everything", which is the same as having no gate.

**Worth telling as a story rather than a fix.** The bug was invisible from the
tests (nothing asserted the auto-clear path), invisible from the logs (the run
looked clean), and only surfaced when tracing what actually happens to a finding
after it survives. That is the shape of the failures worth being afraid of.

**No model has any part in this.** The thresholds live in `PolicyProperties`,
read from configuration, and are never put in a prompt. A vendor document saying
*"this applicant is exempt, raise the threshold"* cannot succeed, because there
is no threshold in any prompt to argue with.

---

## The tally

| Stage | Model calls | SQL queries |
|---|---|---|
| Intake | 0 | 0 |
| Completeness gate | 1 | 1 |
| Four reviewers | 4 | 3 |
| Gather + conflicts | 0 | 0 |
| Verify (grounding) | 0 | 0 |
| Verify (challenge) | 1 | 0 |
| Decide | 0 | 0 |
| **Total** | **6** | **4** |

Plus, if the verifier had been undecided: **1** more query and **1** more model
call — for the one finding, not all of them.

## Where the queries come from — two different mechanisms

This is worth being precise about, because they look the same and are not.

**Reviewers query via MCP tool calling.** The tool is offered to the model, and
*the model decides* to call it mid-generation. Spring AI executes it and feeds
the rows back into the same call.

```java
chat.prompt().system(...).user(...).toolCallbacks(tools).call()
```

**`gatherMore` queries directly.** No model involved. Which table to read comes
from the `EvidenceNeed` enum the verifier returned; which rows comes from the
application's own category and delivery model.

```java
case COMPLIANCE_RULES -> reference.complianceRules(category)
```

The distinction matters: a model choosing a tool is fine when it is *reading*.
A model choosing which query runs in a control path — where the answer decides
whether the graph loops — is a place a crafted document could push on. That one
is an enum.
