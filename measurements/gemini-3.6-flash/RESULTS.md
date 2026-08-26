# Measurement results

Generated 2026-08-26T07:15:55.979610Z

Recall and false positives are reported together on purpose. A system that flags
everything has perfect recall and is useless; one that flags nothing has a perfect
false-positive rate and is equally useless. Either number alone can be gamed.

| # | configuration | fixtures | recall | caught/seeded | false positives | routing |
|---|---|---:|---:|---:|---:|---:|
| 1 | single agent | 7 | 1.0000 | 5/5 | 1 | n/a |
| 2 | gate + four agents | 7 | 0.8000 | 4/5 | 2 | 1.0000 |

## Raw

`config-1.json`

```json
{
  "configuration": 1,
  "label": "single agent, all five areas",
  "recordedAt": "2026-08-25T13:23:52.261651Z",
  "fixtures": 7,
  "seeded": 5,
  "caught": 5,
  "recall": 1.0000,
  "falsePositives": 1,
  "routingMeaningful": false,
  "routingAccuracy": 0.2000,
  "perFixture": [
{
  "fixture": "F01",
  "clean": false,
  "seeded": 1,
  "caught": 1,
  "missed": 0,
  "routedCorrectly": 1,
  "unexpectedFindings": 1,
  "conflicts": 0,
  "discardedUngrounded": 0,
  "reachedCompletion": true
},
{
  "fixture": "F02",
  "clean": true,
  "seeded": 0,
  "caught": 0,
  "missed": 0,
  "routedCorrectly": 0,
  "unexpectedFindings": 0,
  "conflicts": 0,
  "discardedUngrounded": 0,
  "reachedCompletion": true
},
{
  "fixture": "F03",
  "clean": false,
  "seeded": 1,
  "caught": 1,
  "missed": 0,
  "routedCorrectly": 0,
  "unexpectedFindings": 0,
  "conflicts": 0,
  "discardedUngrounded": 0,
  "reachedCompletion": true
},
{
  "fixture": "F06",
  "clean": false,
  "seeded": 1,
  "caught": 1,
  "missed": 0,
  "routedCorrectly": 0,
  "unexpectedFindings": 0,
  "conflicts": 0,
  "discardedUngrounded": 0,
  "reachedCompletion": true
},
{
  "fixture": "F08",
  "clean": false,
  "seeded": 1,
  "caught": 1,
  "missed": 0,
  "routedCorrectly": 0,
  "unexpectedFindings": 1,
  "conflicts": 0,
  "discardedUngrounded": 0,
  "reachedCompletion": true
},
{
  "fixture": "F10",
  "clean": false,
  "seeded": 1,
  "caught": 1,
  "missed": 0,
  "routedCorrectly": 0,
  "unexpectedFindings": 1,
  "conflicts": 0,
  "discardedUngrounded": 0,
  "reachedCompletion": true
},
{
  "fixture": "F12",
  "clean": true,
  "seeded": 0,
  "caught": 0,
  "missed": 0,
  "routedCorrectly": 0,
  "unexpectedFindings": 1,
  "conflicts": 0,
  "discardedUngrounded": 0,
  "reachedCompletion": true
}
  ]
}

```

`config-2.json`

```json
{
  "configuration": 2,
  "label": "gate + four agents, no verifier",
  "recordedAt": "2026-08-26T07:15:55.974791Z",
  "fixtures": 7,
  "seeded": 5,
  "caught": 4,
  "recall": 0.8000,
  "falsePositives": 2,
  "routingMeaningful": true,
  "routingAccuracy": 1.0000,
  "perFixture": [
{
  "fixture": "F01",
  "clean": false,
  "seeded": 1,
  "caught": 1,
  "missed": 0,
  "routedCorrectly": 1,
  "unexpectedFindings": 1,
  "conflicts": 0,
  "discardedUngrounded": 0,
  "reachedCompletion": true
},
{
  "fixture": "F02",
  "clean": true,
  "seeded": 0,
  "caught": 0,
  "missed": 0,
  "routedCorrectly": 0,
  "unexpectedFindings": 1,
  "conflicts": 0,
  "discardedUngrounded": 0,
  "reachedCompletion": true
},
{
  "fixture": "F03",
  "clean": false,
  "seeded": 1,
  "caught": 0,
  "missed": 1,
  "routedCorrectly": 0,
  "unexpectedFindings": 1,
  "conflicts": 0,
  "discardedUngrounded": 0,
  "reachedCompletion": true
},
{
  "fixture": "F06",
  "clean": false,
  "seeded": 1,
  "caught": 1,
  "missed": 0,
  "routedCorrectly": 1,
  "unexpectedFindings": 1,
  "conflicts": 0,
  "discardedUngrounded": 0,
  "reachedCompletion": true
},
{
  "fixture": "F08",
  "clean": false,
  "seeded": 1,
  "caught": 1,
  "missed": 0,
  "routedCorrectly": 1,
  "unexpectedFindings": 0,
  "conflicts": 0,
  "discardedUngrounded": 0,
  "reachedCompletion": true
},
{
  "fixture": "F10",
  "clean": false,
  "seeded": 1,
  "caught": 1,
  "missed": 0,
  "routedCorrectly": 1,
  "unexpectedFindings": 0,
  "conflicts": 0,
  "discardedUngrounded": 0,
  "reachedCompletion": true
},
{
  "fixture": "F12",
  "clean": true,
  "seeded": 0,
  "caught": 0,
  "missed": 0,
  "routedCorrectly": 0,
  "unexpectedFindings": 1,
  "conflicts": 0,
  "discardedUngrounded": 0,
  "reachedCompletion": true
}
  ]
}

```

