# Measurement results

**Model: `openai/gpt-oss-120b`.** Every row below was measured on it. Numbers from a different model live in a sibling directory and are NOT comparable with these - the comparison here is between configurations on one model.

Generated 2026-08-29T15:00:27.108846Z

Recall and false positives are reported together on purpose. A system that flags
everything has perfect recall and is useless; one that flags nothing has a perfect
false-positive rate and is equally useless. Either number alone can be gamed.

| # | configuration | fixtures | recall | caught/seeded | FP (all) | FP (actionable) | confirmations | routing | median latency |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|
| 1 | single agent | 5 | 0.7143 | 10/14 | 6 | 6 | 0 | n/a | 28 s |
| 2 | gate + four agents | 5 | 1.0000 | 14/14 | 31 | 0 | 31 | 1.0000 | 88 s |
| 3 | gate + four agents + verifier | 5 | 0.9286 | 13/14 | 30 | - | - | 1.0000 | - |

## Raw

`config-1.json`

```json
{
  "configuration": 1,
  "label": "single agent, all five areas",
  "recordedAt": "2026-08-29T15:00:26.930139Z",
  "fixtures": 5,
  "incompleteFixtures": 0,
  "seeded": 14,
  "caught": 10,
  "recall": 0.7143,
  "falsePositives": 6,
  "actionableFalsePositives": 6,
  "confirmatoryFindings": 0,
  "falsePositiveNote": "falsePositives counts EVERY finding on a clean pack. actionableFalsePositives counts only those at MAJOR or above - the ones that would actually stop a vendor. The difference is confirmatoryFindings: INFO entries saying the pack passed a check, which are an audit trail rather than an accusation. Both are reported because narrowing the metric after seeing the number it made look bad is the move PREDICTIONS.md exists to prevent.",
  "routingMeaningful": false,
  "routingAccuracy": 0.1000,
  "medianWallClockMs": 12,
  "wallClockCaveat": "Median wall clock per fixture, NOT the sum of model calls - configuration 2 runs four reviewers concurrently, so cost and latency do not scale together. Comparable across configurations ONLY within a single uncached, unthrottled run: a cached call returns in about a millisecond and a rate-limited one spends 20 seconds in backoff, and either dominates this number completely. When this number is implausibly small the run was served from cache - read medianCriticalPathMs instead.",
  "medianCriticalPathMs": 27602,
  "criticalPathNote": "The same journey rebuilt from the per-call audit records: gate plus the SLOWEST concurrent reviewer, not their sum. Survives a cached re-run, because a cache hit reports the original call's duration rather than the lookup - which is why the 2026-08-28 timings were recoverable at all after configurations 1 and 2 were regenerated from cache and their wall clock collapsed to 8ms and 22ms. EXCLUDES THE VERIFIER, which writes no audit entry, so configuration 3's figure is a floor and not a total.",
  "perFixture": [
{
  "fixture": "F15",
  "clean": true,
  "seeded": 0,
  "caught": 0,
  "missed": 0,
  "routedCorrectly": 0,
  "unexpectedFindings": 0,
  "conflicts": 0,
  "discardedUngrounded": 0,
  "reachedCompletion": true,
  "wallClockMs": 316,
  "criticalPathMs": 5379
},
{
  "fixture": "F16",
  "clean": true,
  "seeded": 0,
  "caught": 0,
  "missed": 0,
  "routedCorrectly": 0,
  "unexpectedFindings": 6,
  "conflicts": 0,
  "discardedUngrounded": 0,
  "reachedCompletion": true,
  "wallClockMs": 13,
  "criticalPathMs": 6243
},
{
  "fixture": "F17",
  "clean": false,
  "seeded": 4,
  "caught": 2,
  "missed": 2,
  "routedCorrectly": 0,
  "unexpectedFindings": 1,
  "conflicts": 0,
  "discardedUngrounded": 0,
  "reachedCompletion": true,
  "wallClockMs": 9,
  "criticalPathMs": 30697
},
{
  "fixture": "F18",
  "clean": false,
  "seeded": 5,
  "caught": 4,
  "missed": 1,
  "routedCorrectly": 1,
  "unexpectedFindings": 1,
  "conflicts": 0,
  "discardedUngrounded": 0,
  "reachedCompletion": true,
  "wallClockMs": 12,
  "criticalPathMs": 36286
},
{
  "fixture": "F20",
  "clean": false,
  "seeded": 5,
  "caught": 4,
  "missed": 1,
  "routedCorrectly": 0,
  "unexpectedFindings": 0,
  "conflicts": 0,
  "discardedUngrounded": 0,
  "reachedCompletion": true,
  "wallClockMs": 6,
  "criticalPathMs": 27602
}
  ]
}

```

`config-2.json`

```json
{
  "configuration": 2,
  "label": "gate + four agents, no verifier",
  "recordedAt": "2026-08-29T15:00:27.106179Z",
  "fixtures": 5,
  "incompleteFixtures": 0,
  "seeded": 14,
  "caught": 14,
  "recall": 1.0000,
  "falsePositives": 31,
  "actionableFalsePositives": 0,
  "confirmatoryFindings": 31,
  "falsePositiveNote": "falsePositives counts EVERY finding on a clean pack. actionableFalsePositives counts only those at MAJOR or above - the ones that would actually stop a vendor. The difference is confirmatoryFindings: INFO entries saying the pack passed a check, which are an audit trail rather than an accusation. Both are reported because narrowing the metric after seeing the number it made look bad is the move PREDICTIONS.md exists to prevent.",
  "routingMeaningful": true,
  "routingAccuracy": 1.0000,
  "medianWallClockMs": 23,
  "wallClockCaveat": "Median wall clock per fixture, NOT the sum of model calls - configuration 2 runs four reviewers concurrently, so cost and latency do not scale together. Comparable across configurations ONLY within a single uncached, unthrottled run: a cached call returns in about a millisecond and a rate-limited one spends 20 seconds in backoff, and either dominates this number completely. When this number is implausibly small the run was served from cache - read medianCriticalPathMs instead.",
  "medianCriticalPathMs": 87780,
  "criticalPathNote": "The same journey rebuilt from the per-call audit records: gate plus the SLOWEST concurrent reviewer, not their sum. Survives a cached re-run, because a cache hit reports the original call's duration rather than the lookup - which is why the 2026-08-28 timings were recoverable at all after configurations 1 and 2 were regenerated from cache and their wall clock collapsed to 8ms and 22ms. EXCLUDES THE VERIFIER, which writes no audit entry, so configuration 3's figure is a floor and not a total.",
  "perFixture": [
{
  "fixture": "F15",
  "clean": true,
  "seeded": 0,
  "caught": 0,
  "missed": 0,
  "routedCorrectly": 0,
  "unexpectedFindings": 16,
  "conflicts": 0,
  "discardedUngrounded": 0,
  "reachedCompletion": true,
  "wallClockMs": 69,
  "criticalPathMs": 64717
},
{
  "fixture": "F16",
  "clean": true,
  "seeded": 0,
  "caught": 0,
  "missed": 0,
  "routedCorrectly": 0,
  "unexpectedFindings": 15,
  "conflicts": 0,
  "discardedUngrounded": 0,
  "reachedCompletion": true,
  "wallClockMs": 25,
  "criticalPathMs": 86123
},
{
  "fixture": "F17",
  "clean": false,
  "seeded": 4,
  "caught": 4,
  "missed": 0,
  "routedCorrectly": 4,
  "unexpectedFindings": 1,
  "conflicts": 0,
  "discardedUngrounded": 0,
  "reachedCompletion": true,
  "wallClockMs": 20,
  "criticalPathMs": 87780
},
{
  "fixture": "F18",
  "clean": false,
  "seeded": 5,
  "caught": 5,
  "missed": 0,
  "routedCorrectly": 5,
  "unexpectedFindings": 2,
  "conflicts": 1,
  "discardedUngrounded": 0,
  "reachedCompletion": true,
  "wallClockMs": 20,
  "criticalPathMs": 114143
},
{
  "fixture": "F20",
  "clean": false,
  "seeded": 5,
  "caught": 5,
  "missed": 0,
  "routedCorrectly": 5,
  "unexpectedFindings": 0,
  "conflicts": 0,
  "discardedUngrounded": 0,
  "reachedCompletion": true,
  "wallClockMs": 23,
  "criticalPathMs": 88920
}
  ]
}

```

`config-3.json`

```json
{
  "configuration": 3,
  "label": "gate + four agents + verifier",
  "recordedAt": "2026-08-28T07:27:42.676031Z",
  "fixtures": 5,
  "incompleteFixtures": 0,
  "seeded": 14,
  "caught": 13,
  "recall": 0.9286,
  "falsePositives": 30,
  "routingMeaningful": true,
  "routingAccuracy": 1.0000,
  "medianWallClockMs": 180412,
  "wallClockCaveat": "Median wall clock per fixture, NOT the sum of model calls - configuration 2 runs four reviewers concurrently, so cost and latency do not scale together. Comparable across configurations ONLY within a single uncached, unthrottled run: a cached call returns in about a millisecond and a rate-limited one spends 20 seconds in backoff, and either dominates this number completely.",
  "perFixture": [
{
  "fixture": "F15",
  "clean": true,
  "seeded": 0,
  "caught": 0,
  "missed": 0,
  "routedCorrectly": 0,
  "unexpectedFindings": 15,
  "conflicts": 0,
  "discardedUngrounded": 1,
  "reachedCompletion": true,
  "wallClockMs": 66
},
{
  "fixture": "F16",
  "clean": true,
  "seeded": 0,
  "caught": 0,
  "missed": 0,
  "routedCorrectly": 0,
  "unexpectedFindings": 15,
  "conflicts": 0,
  "discardedUngrounded": 0,
  "reachedCompletion": true,
  "wallClockMs": 167
},
{
  "fixture": "F17",
  "clean": false,
  "seeded": 4,
  "caught": 4,
  "missed": 0,
  "routedCorrectly": 4,
  "unexpectedFindings": 1,
  "conflicts": 0,
  "discardedUngrounded": 0,
  "reachedCompletion": true,
  "wallClockMs": 220679
},
{
  "fixture": "F18",
  "clean": false,
  "seeded": 5,
  "caught": 5,
  "missed": 0,
  "routedCorrectly": 5,
  "unexpectedFindings": 2,
  "conflicts": 1,
  "discardedUngrounded": 0,
  "reachedCompletion": true,
  "wallClockMs": 203826
},
{
  "fixture": "F20",
  "clean": false,
  "seeded": 5,
  "caught": 4,
  "missed": 1,
  "routedCorrectly": 4,
  "unexpectedFindings": 0,
  "conflicts": 0,
  "discardedUngrounded": 1,
  "reachedCompletion": true,
  "wallClockMs": 180412
}
  ]
}

```

