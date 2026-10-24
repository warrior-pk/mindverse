# Mindverse

A standalone, local-first second-brain app for Android (native Kotlin +
Jetpack Compose). Mindverse captures content from your device, processes it
into structured **Memories**, and makes them searchable and Ask-able entirely
on-device, with time-sensitive **Pins** surfaced proactively.

**Author:** [warrior-pk](https://github.com/warrior-pk)
**License:** Apache-2.0

- **Source code:** [github.com/warrior-pk/mindverse](https://github.com/warrior-pk/mindverse)
- **Project site / showcase:** [mindverse.shipyu.dev](https://mindverse.shipyu.dev)

## Status

Early spine build — see [docs/adr/](docs/adr/) for architecture decisions,
[GLOSSARY.md](GLOSSARY.md) for normative vocabulary, and
`.scratch/mindverse-v1/spec.md` for the ratified product spec.

## Building

Requires JDK 17+ and the Android SDK (API 36). Two distribution flavors,
both buildable with no closed blobs in the `fdroid` flavor:

```bash
./gradlew assemblePlayDebug
./gradlew assembleFdroidDebug
```
