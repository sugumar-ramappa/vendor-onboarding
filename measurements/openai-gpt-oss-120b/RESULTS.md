# Measurement results

**Model: `openai/gpt-oss-120b`.** Every row below was measured on it. Numbers from
a different model live in a sibling directory and are NOT comparable with these -
the comparison here is between configurations on one model.

Recall and false positives are reported together on purpose. A system that flags
everything has perfect recall and is useless; one that flags nothing has a perfect
false-positive rate and is equally useless. Either number alone can be gamed.

| # | configuration | fixtures | recall | caught/seeded | false positives | routing |
|---|---|---:|---:|---:|---:|---:|
| 1 | single agent | 14 | 0.8182 | 9/11 | 4 | n/a |
| 2 | gate + four agents | - | not measured | - | - | - |
| 3 | gate + four agents + verifier | - | not measured | - | - | - |

## Why 2 and 3 are blank rather than filled in

They ran, produced numbers, and **those numbers were discarded**. Both are in
`../quarantine-incomplete/` rather than deleted, so the decision can be checked.

Groq's free tier caps **tokens per day at 200,000**, not requests per minute as
assumed when this provider was adopted. At roughly 4,400 tokens per review call
that is about 45 calls a day, and the full experiment - three configurations over
14 fixtures - is 112 calls, or around 493,000 tokens. It does not fit in a day.

The cap was reached partway through configuration 2. Every subsequent reviewer
failed with

```
429: tokens per day (TPD): Limit 200000, Used 196902
```

so six of the fourteen fixtures - F09 to F14 - recorded `reachedCompletion:
false`. The recorded figures were:

```
2  gate + four agents              recall 0.55 (6/11)   false positives 7
3  gate + four agents + verifier   recall 0.55 (6/11)   false positives 2
```

**Both are meaningless, and the second is actively misleading.** Configuration 3
appears to cut false positives from 7 to 2, which is exactly the improvement the
verifier exists to produce - and it is an artefact. Those false positives were not
refuted by a verifier; the reviewers that would have raised them never ran. A
number that moves the way the hypothesis predicts, for a reason unrelated to the
hypothesis, is the most dangerous kind of result a measurement can produce.

Configuration 1 completed before the cap and had **zero** reviewer failures on all
14 fixtures, so it stands.

## What this cost, and the mistake behind it

Roughly 197,000 tokens, of which about 88,000 went on an aborted configuration 2
run that was corrupted by a separate defect (`logistics-v2` instructing a tool
call that the measurement had disabled - see the engineering log). Nearly half a
day's budget spent on a run whose output was discarded.

The underlying error was assuming a "free tier" means one kind of limit. It does
not:

```
Gemini   20 requests/day          -> a SCHEDULING problem, six days for this experiment
Groq     8,000 tokens/minute      -> a THROTTLING problem, ~1.6 calls/min
         200,000 tokens/day       -> a SCHEDULING problem again, ~2.5 days
```

The per-minute limit was found and planned around. The per-day limit was not
looked for, because the per-minute one had already been accepted as "the"
constraint. **Both existed the whole time.** Checking the provider's quota page
before spending 197,000 tokens would have cost two minutes.
