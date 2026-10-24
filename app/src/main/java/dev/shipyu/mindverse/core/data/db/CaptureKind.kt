package dev.shipyu.mindverse.core.data.db

/**
 * Why the Capture came into the pipeline. The trigger vocabulary is normative per
 * `GLOSSARY.md` (Trigger); the kind list mirrors ticket 02's row contract
 * (share/image/text/pdf/audio/notification).
 */
enum class CaptureKind {
    SHARE,
    IMAGE,
    TEXT,
    PDF,
    AUDIO,
    NOTIFICATION,
}
