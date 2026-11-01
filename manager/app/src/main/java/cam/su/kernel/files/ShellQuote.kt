package cam.su.kernel.files

/**
 * [value] as one sh word: single quotes, a quote inside closed, escaped and reopened. The one copy
 * every command line built for a root shell uses (a hand-made one wrote `'''` and broke).
 */
fun shellQuote(value: String) = "'" + value.replace("'", "'\\''") + "'"
