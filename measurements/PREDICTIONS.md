# Predictions, written before the run

Dated 2026-08-26, **before** the dense fixtures were measured. Committed first on
purpose.

## Why this file exists

The fixture set was redesigned *after* seeing a result that made multi-agent look
worse than its own baseline. That is exactly the shape of retuning an experiment
until the answer improves, and saying "but my reasons were good" afterwards is
not checkable.

So the reasons are written down here first, with a predicted direction for each
fixture class. If the numbers contradict these predictions, that is the finding
and it goes in the README as the headline.

## What was wrong with the original design

The original 11 defective fixtures each carried **exactly one defect, one per
review area**. That design answers *"does the right document reach the right
reviewer?"* - which is what `routingAccuracy` measures, and it scored **1.0000**.

It cannot answer *"does splitting work across specialists beat one generalist?"*,
because with one defect there is nothing to split: four of the five reviewers have
nothing to find on every fixture. The experiment could not have shown multi-agent
winning.

It also made conflict detection untestable. A conflict needs two findings that
contradict each other, and one defect cannot contradict anything. Zero conflicts
were reported across every fixture in every configuration - not because the
detector is broken, but because it was never given anything to detect.

## The three fixture classes and their predictions

### Shallow - one defect (F01-F14, already measured)

**Prediction: multi-agent shows no advantage and may lose.**

With one thing wrong, specialisation buys nothing and costs five model calls
against one. Any difference should be noise or overhead. This class is the
control, and the Gemini run behaved as predicted: 1.00 for the single agent
against 0.80 for four agents plus a gate, with the entire gap traceable to one
rulebook defect rather than to the architecture.

### Dense - four or five defects across areas (F17, F18)

**Prediction: multi-agent WINS on recall.**

This is the claim the project exists to test and has never tested. A single call
asked to check five areas tends to report the most salient one or two problems and
stop; five reviewers each have exactly one job and no reason to stop early. If
specialisation has value anywhere, it is here.

Both fixtures deliberately have **every mandatory document present**, so the
completeness gate passes and the four substantive reviewers actually run. The
defects are in what the documents *say*, not in which are missing - otherwise the
gate short-circuits and the fixture measures the gate instead of the reviewers.

**If multi-agent does not win here, the architecture has no measured
justification**, and that belongs in the README as the headline rather than
buried.

### Cross-cutting - one defect visible only by joining two areas (F19)

**Prediction: multi-agent LOSES.**

F19's insurance certificate is ample, in force and unexpired - it covers Great
Britain only. The agreed direct-to-store delivery list includes Belfast and
Londonderry. Neither document is wrong on its own; the defect exists only in the
join, and the two documents belong to two different reviewers.

The project's stated reason for isolating reviewers is that a shared context
anchors later judgements on earlier findings. **The same isolation prevents the
join.** The single agent sees both documents in one context and should find this;
isolated reviewers each see half.

This is a predicted *cost* of the architecture, and finding it would be a
stronger result than another win.

### Conditional-requirement regression (F15, F16)

**Prediction: both clean, no findings.**

Both omit a mandatory document whose condition does not hold - a timber
certificate for a vendor selling steel bolts, a safety data sheet for a vendor
selling brushes. Before the `applies_when` migration these would have failed:
the gate reads `mandatory = true`, flags BLOCKING, and short-circuits four
reviewers. That is the exact cascade that cost configuration 2 both of its
headline numbers on F03.

F16 is the deliberate sibling of F11 - same category, same missing document,
**opposite** correct answer, because F11 has a hazardous SKU and F16 does not.
The pair pins the conditionality from both sides.

### Conflict (probe, inside F18)

**Prediction: uncertain, and it is recorded as an observation rather than scored.**

A conflict cannot be planted deterministically. `ConflictDetector` requires two
findings from **different** areas, on the same SKU or document, with a severity
gap of at least **2** - `INFO` vs `MAJOR`, or `MINOR` vs `BLOCKING`. Severity is
the model's judgement, not the fixture's.

F18 makes it likely by pairing, on SKU `KEL-AER-500`, a defect that should read as
MINOR (one kilogram over a handling limit) with one that should read as BLOCKING (a
flammable aerosol with no CLP or GHS classification). Note that MINOR against
MAJOR is a gap of only 1 and would **not** fire - the first draft of this fixture
had exactly that flaw.

Zero conflicts would be a finding about severity calibration, not a failed
fixture.

## What would make this dishonest

- Changing these predictions after seeing results
- Dropping a fixture class because it produced an unflattering number
- Reporting dense-pack results without the shallow-pack contrast

The shallow numbers stay in the repository regardless of what the dense ones say.

---

# Verifier calibration, predicted 2026-08-29 before running

## Why a second predictions section exists

The three-configuration run finished and reported that the verifier refuted
**zero** findings out of 48, across two runs. That reads as a result and is not
one. Every finding it was shown was essentially correct - configuration 2 found
14 of 14 planted defects, and its 31 clean-pack findings were all `INFO`
confirmations. **A well-calibrated verifier shown only correct findings should
refute nothing.** Zero is what a working verifier and a broken one both produce.

This is the identical mistake `prompt-eval` had already diagnosed in its own
judge, in its own words: *with only correct answers to grade, false accepts are
undetectable - you cannot observe a judge waving through a wrong answer you never
showed it.* The same blind spot, two projects apart.

## The set

Balanced, the way `prompt-eval`'s judge calibration was balanced.

**Known-false findings.** F16 is a clean pack - a brush vendor with no hazardous
SKU - so **every** finding raised on it is false by construction, no labelling
judgement required. The single agent produced six there, four of them `BLOCKING`,
including a demand for safety data sheets covering a hazard the fixture says does
not exist (`hazardous = false`).

**Known-true findings.** The findings on F17, F18 and F20 that matched planted
defects. These are true by construction too: the fixture author planted them.

## The predictions

**1. The verifier refutes fewer than half the known-false findings.**

Because it is the same model that produced them. `application-groq.yml` pins one
model for every role - `openai/gpt-oss-120b` reviews, and `openai/gpt-oss-120b`
checks the review. A model asked whether its own confident claim is wrong shares
the blind spot that produced the claim. Self-verification is the weakest form of
verification and this run uses it.

**If it refutes most of them, this prediction is wrong and that is the finding**:
the verifier works, it had nothing to do on the real measurement, and the honest
report becomes "it does not ship because it insures against a failure my
reviewers did not produce" rather than "it does not work".

**2. It refutes almost none of the known-true findings.**

Two runs of 48 correct findings produced zero refutations, so on this evidence
the verifier is heavily biased toward letting things stand. That bias should
protect true findings even if it fails to catch false ones - which is exactly the
shape `prompt-eval` found in its v2 judge: generous, agreeable, and dangerous.

**3. The two together are the actual result.** Refuting nothing and refuting
everything are both useless. What matters is the gap between the two rates, which
is the same reason `prompt-eval` reports false accepts and false rejects as
separate columns rather than one agreement score.

## What would make this dishonest

- Reporting the false-finding rate without the true-finding rate
- Swapping the model after seeing the result and reporting only the better run
- Quietly widening what counts as "refuted"

**If the verifier fails this, it stays in the repository.** A component that was
built, measured and found not to work is worth more as a recorded negative than
as a deleted mistake.

---

# Configuration 4, predicted 2026-08-29 before running

## Why a fourth configuration exists

The calibration answered the question it was built for - the verifier refuted 4
of 6 fabricated findings and destroyed 0 of 7 true ones - but it answered it on a
bench. The verifier was handed one finding at a time, with no grounding check in
front of it and no `gatherMore` cycle behind it. **Configuration 4 asks the same
question inside the real pipeline**: single agent, then verifier, exactly as
configuration 3 does for the four specialists.

The point is not to find a better architecture. It is that the calibration
implies something specific and untested - that the verifier's value depends
entirely on which reviewers it is attached to - and an implication is not a
measurement.

## The predictions

**1. Recall stays at 10 of 14. Exactly.**

`VerifierAgent` can only remove findings; it has no path that creates one. So
configuration 4's recall is bounded above by configuration 1's, and any figure
*higher* than 10/14 means something is wrong with the harness rather than good
about the architecture. A number that cannot improve is the cleanest kind of
prediction, and it is stated first so that a surprise there is read as a bug.

**2. Actionable false positives fall from 6 to roughly 2.**

Directly from the calibration: 4 of 6 refuted. If the pipeline does better than
the bench it will be because `gatherMore` resolves the two that were blocked on
missing reference data, in which case this lands nearer 0 - and that would be
evidence the bench *understated* the verifier, which is worth knowing.

**3. Configuration 2 still wins, and the reason is the finding.**

10/14 with a clean output beats 10/14 with a dirty one, and loses to 14/14. So:
**specialisation buys recall, and no guardrail recovers it.** A verifier can only
tidy what the reviewers said - it cannot make them see what they never looked at.
If that holds, the honest summary of this whole project is that the expensive
architecture is worth its cost for what it *finds*, not for what it avoids
claiming.

**4. Configuration 3 reaches 14 of 14 in the same run.**

Not a prediction about the architecture - a regression check on today's grounding
fixes. Configuration 3's missing defect was never refuted by the verifier; it was
discarded twice by the citation check, first for re-rendering a table row and
then for citing the retailer's rulebook. Both are now fixed. **If it is still
13/14, one of those fixes does not work** and the number is telling me so.

## What would make this dishonest

- Reporting configuration 4's false-positive improvement without its recall ceiling
- Presenting it as a rival to configuration 2 when its recall cannot reach it
- Dropping it if the false positives do not improve

**Configuration 4 is predicted to lose.** It is worth running because it explains
*why* the winner wins.
