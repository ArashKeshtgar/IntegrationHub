# olis-sim

A stand-in for the OLIS receiver, so the hub's ORU^R01 export can be developed and tested without the real service.
One file, no dependencies:

```
java tools/olis-sim/OlisSim.java [--port 2576] [--bind 127.0.0.1] [--out DIR] [--mode accept|error|reject|silent]
```

- `accept` answers `AA` (default), `error` answers `AE`, `reject` answers `AR`, `silent` reads the message and never
  answers (the hub times out and retries).
- It prints one line per message with the control id and the number of OBR and OBX segments, never the content.
- `--out DIR` keeps each message as `DIR/{control id}.hl7`. Only use it with synthetic data.
- It checks nothing about the message except MSH-10. It is **not** a model of what the real OLIS accepts.

The hub tests start it as a process (`Hl7OutboundTest`).
