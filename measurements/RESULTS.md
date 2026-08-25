# Measurement results

Generated 2026-08-25T13:31:31.725150Z

Recall and false positives are reported together on purpose. A system that flags
everything has perfect recall and is useless; one that flags nothing has a perfect
false-positive rate and is equally useless. Either number alone can be gamed.

| # | configuration | fixtures | recall | caught/seeded | false positives | routing |
|---|---|---:|---:|---:|---:|---:|
| 1 | single agent | 7 | 1.0000 | 5/5 | 1 | n/a |
| 2 | gate + four agents | 2 | 0.5000 | 1/2 | 0 | 1.0000 |

> **NOT COMPARABLE YET.** These configurations were measured over different numbers of fixtures (7, 2). Recall is only comparable across configurations run on the same set, so treat the rows above as progress, not as a result.

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
  "recordedAt": "2026-08-25T13:31:31.720225Z",
  "fixtures": 2,
  "seeded": 2,
  "caught": 1,
  "recall": 0.5000,
  "falsePositives": 0,
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
}
  ]
}

```

