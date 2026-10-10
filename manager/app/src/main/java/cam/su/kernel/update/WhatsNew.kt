package cam.su.kernel.update

/** What Home does on open: entries to show in "What's new", and the version to remember (null = keep). */
data class WhatsNewDecision(val show: List<ChangelogEntry>, val saveLastSeen: String?)

/**
 * Show the packed changelog once after an update. Fresh installs, dev builds ("3.0.0-5-gabc",
 * hashes) and versions without an entry show nothing.
 */
fun decideWhatsNew(lastSeen: String?, current: String, entries: List<ChangelogEntry>): WhatsNewDecision {
    if (parseSemver(current) == null) return WhatsNewDecision(emptyList(), null)
    if (lastSeen == null) return WhatsNewDecision(emptyList(), current)
    if (lastSeen == current) return WhatsNewDecision(emptyList(), null)
    return WhatsNewDecision(ChangelogParser.entriesNewerThan(entries, lastSeen, current), current)
}
