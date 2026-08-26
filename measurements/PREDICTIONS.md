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
