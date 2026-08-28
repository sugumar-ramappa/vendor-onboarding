# Measurement results

**Model: `openai/gpt-oss-120b`.** Every row below was measured on it. Numbers from a different model live in a sibling directory and are NOT comparable with these - the comparison here is between configurations on one model.

Generated 2026-08-28T07:53:29.635601Z

Recall and false positives are reported together on purpose. A system that flags
everything has perfect recall and is useless; one that flags nothing has a perfect
false-positive rate and is equally useless. Either number alone can be gamed.

| # | configuration | fixtures | recall | caught/seeded | FP (all) | FP (actionable) | confirmations | routing |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| 1 | single agent | 5 | 0.7143 | 10/14 | 6 | 6 | 0 | n/a |
| 2 | gate + four agents | 5 | 1.0000 | 14/14 | 31 | 0 | 31 | 1.0000 |
| 3 | gate + four agents + verifier | 5 | 0.9286 | 13/14 | 30 | - | - | 1.0000 |

## Raw

`config-1.json`

```json
{
  "configuration": 1,
  "label": "single agent, all five areas",
  "recordedAt": "2026-08-28T07:53:29.481191Z",
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
  "medianWallClockMs": 8,
  "wallClockCaveat": "Median wall clock per fixture, NOT the sum of model calls - configuration 2 runs four reviewers concurrently, so cost and latency do not scale together. Comparable across configurations ONLY within a single uncached, unthrottled run: a cached call returns in about a millisecond and a rate-limited one spends 20 seconds in backoff, and either dominates this number completely.",
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
  "wallClockMs": 287
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
  "wallClockMs": 21
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
  "wallClockMs": 8
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
  "wallClockMs": 6
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
  "wallClockMs": 6
}
  ]
}

```

`config-2.json`

```json
{
  "configuration": 2,
  "label": "gate + four agents, no verifier",
  "recordedAt": "2026-08-28T07:53:29.635202Z",
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
  "medianWallClockMs": 22,
  "wallClockCaveat": "Median wall clock per fixture, NOT the sum of model calls - configuration 2 runs four reviewers concurrently, so cost and latency do not scale together. Comparable across configurations ONLY within a single uncached, unthrottled run: a cached call returns in about a millisecond and a rate-limited one spends 20 seconds in backoff, and either dominates this number completely.",
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
  "wallClockMs": 60
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
  "wallClockMs": 26
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
  "wallClockMs": 22
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
  "wallClockMs": 21
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
  "wallClockMs": 17
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

