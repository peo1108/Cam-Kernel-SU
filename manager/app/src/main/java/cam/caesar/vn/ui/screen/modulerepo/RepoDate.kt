package cam.caesar.vn.ui.screen.modulerepo

import java.text.DateFormat
import java.time.Instant
import java.util.Date
import java.util.Locale

/**
 * A release time from the module feed (ISO 8601, `2026-11-03T02:01:41Z`) as a date in the phone's
 * own format (`3 thg 11, 2026`). Anything that does not parse is shown as it came.
 */
internal fun repoDate(iso: String, locale: Locale = Locale.getDefault()): String {
    if (iso.isBlank()) return iso
    val instant = runCatching { Instant.parse(iso.trim()) }.getOrNull() ?: return iso
    return DateFormat.getDateInstance(DateFormat.MEDIUM, locale).format(Date.from(instant))
}
