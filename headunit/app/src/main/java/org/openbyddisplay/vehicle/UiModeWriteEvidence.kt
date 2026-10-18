package org.openbyddisplay.vehicle

/** Pure evidence rules, separated from Android/provider access for offline regression tests. */
internal object UiModeWriteEvidence {
    fun buildWriteCommand(key: String, value: Int): String {
        require(key == "support_change_system_uimode" && value == 1) { "Only the native settings support bit may be written" }
        // Only insert when the provider explicitly reports a missing row. Never append
        // after a successful update or a permission error.
        return """
            updated=${'$'}(content update --uri content://carsettings/global --bind value:i:$value --where "key='$key'" 2>&1)
            update_rc=${'$'}?
            printf '%s\n' "${'$'}updated"
            if [ "${'$'}update_rc" -ne 0 ]; then exit "${'$'}update_rc"; fi
            case "${'$'}updated" in
              'Updated 0 rows.'|'Updated 0 rows')
                content insert --uri content://carsettings/global --bind key:s:$key --bind value:i:$value || exit ${'$'}?
                ;;
            esac
            content query --uri content://carsettings/global --where "key='$key'" --projection value
        """.trimIndent()
    }

    fun readBackMatches(actual: Int?, expected: Int): Boolean = actual == expected

    fun adbConfirmed(shellOk: Boolean, output: String, readBack: Int?, expected: Int): Boolean {
        // A readable provider result takes precedence over potentially stale shell output.
        if (readBack != null) return readBackMatches(readBack, expected)
        if (!shellOk) return false
        val row = Regex("""^(?:Row:\s*\d+\s+)?value=(-?\d+)\s*$""")
        val values = output.lineSequence().map { it.trim() }
            .mapNotNull { row.matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() }.toList()
        // A prefix (value=10), error message or duplicate provider rows is not confirmation.
        return values.size == 1 && values.single() == expected
    }
}
