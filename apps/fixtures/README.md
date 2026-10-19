# Shared native fixtures

One JSON file per domain, consumed by Android JUnit and iOS XCTest:

```json
{
  "cases": [
    { "name": "descriptive unique case name", "input": {}, "expected": {} }
  ]
}
```

`input` and `expected` are domain-specific JSON values. Keep fixtures deterministic,
platform independent and free of real wallet secrets. `scaffold.json` is a loader
smoke test; future domains should exercise actual shared behavior.

Android adds this directory to its test resources. `SharedFixtures.load("domain")`
parses the envelope and returns named cases without depending on the working directory.
