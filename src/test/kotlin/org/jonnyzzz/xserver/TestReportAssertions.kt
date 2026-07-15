package org.jonnyzzz.xserver

import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal fun assertNoUnsupportedRequests(text: String, label: String) {
    val marker = "Unsupported requests:\n"
    val markerOffset = text.indexOf(marker)
    assertTrue(markerOffset >= 0, "$label must expose an unsupported-request inventory")
    val entries = text
        .substring(markerOffset + marker.length)
        .substringBefore("\n\n")
        .lineSequence()
        .filter { it.isNotBlank() }
        .toList()
    assertEquals(listOf("- None."), entries, "$label must not issue unsupported X11 requests; inventory=$entries")
}
