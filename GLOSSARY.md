# Mindverse

A system-wide second-brain app: it captures content from the user's device, processes it into structured, searchable, retrievable memories, and surfaces the time-sensitive ones proactively. Local-first; Android-first.

## Language

**Capture**:
The raw, untouched content ingested at the moment of a trigger (screenshot bitmap, shared URL, voice audio, notification text) together with its source context (originating app, deep link/URL, timestamp).
_Avoid_: Memory, snapshot, item

**Memory**:
The processed, structured card that derives from one or more Captures — summary, title, tags, extracted entities — and is what search, Ask, and Collections operate on.
_Avoid_: Capture (for processed items), card, note

**Ask**:
The conversational retrieval mode: a natural-language question answered over the user's Memories only, grounded strictly in retrieved Memories with visible source references.
_Avoid_: ASK Mode (as two words), chat, AI search

**Entity**:
A structured field extracted from a Capture and attached to its Memory (date, price, place, person, ticket detail); typed and individually addressable, powering actions and filters.
_Avoid_: metadata (as a catch-all), hashtag

**Collection**:
A user-facing grouping of Memories (e.g. a topic folder or trip), optionally with synthesized aggregates over its members.
_Avoid_: Folder, album, tag group

**Pin**:
A transient Memory that is surfaced prominently (status chip / live card) while it is time-sensitive and expires with a TTL; distinct from storage lifetime, which is governed by the underlying Memory.
_Avoid_: Sticky, chip, highlight

**Import**:
Bulk ingestion of content that already existed on the device before a Trigger fired (e.g. scanning the Screenshots album), producing Captures at the same upstream point as live captures but only after an explicit, album-scoped user opt-in.
_Avoid_: Backfill, scan, migration

**Trigger**:
A user or system event that starts ingestion: share-sheet share, Quick Settings tile, screenshot watch, voice memo, or a future accessibility/hardware trigger.
_Avoid_: Capture (as a verb for the trigger event), hook
