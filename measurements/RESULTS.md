# Measurement results

Generated 2026-08-25T13:19:08.156428Z

Recall and false positives are reported together on purpose. A system that flags
everything has perfect recall and is useless; one that flags nothing has a perfect
false-positive rate and is equally useless. Either number alone can be gamed.

| # | configuration | recall | caught/seeded | false positives | routing |
|---|---|---:|---:|---:|---:|
| 1 | single agent | 1.0000 | 1/1 | 0 | n/a |

## Raw

`config-1.json`

```json
{
  "configuration": 1,
  "label": "single agent, all five areas",
  "recordedAt": "2026-08-25T13:19:08.155706Z",
  "fixtures": 1,
  "seeded": 1,
  "caught": 1,
  "recall": 1.0000,
  "falsePositives": 0,
  "routingMeaningful": false,
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
}
  ]
}

```

