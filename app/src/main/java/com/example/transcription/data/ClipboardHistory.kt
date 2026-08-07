package com.example.transcription.data

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

data class ClipboardItem(
    val id: String,
    val text: String,
    /** A content URI for image clips; `null` for plain text. */
    val imageUri: String? = null,
    val copiedAt: Long = 0L,
    val pinned: Boolean = false
) {
    val isImage: Boolean get() = imageUri != null
    val preview: String
        get() = if (isImage) "Image" else text.lineSequence().firstOrNull().orEmpty().take(120)
}

/**
 * The keyboard's clipboard panel, backed by what the app is allowed to see.
 *
 * Android has no clipboard-history API: `ClipboardManager` exposes only the
 * current clip, and since Android 10 reading it at all requires being the
 * focused app or the active IME. Every keyboard that offers this — Gboard
 * included — therefore builds its own list by sampling the current clip while
 * it runs. Anything copied while a different keyboard is active is invisible to
 * us by design, not by omission.
 *
 * Text is stored inline; images are stored as the content URI the source app
 * granted, so no pixels are copied into our sandbox.
 */
class ClipboardHistory(context: Context) {
    private val preferences = context.getSharedPreferences("clipboard", Context.MODE_PRIVATE)
    private val _items = MutableStateFlow(load())
    val items: StateFlow<List<ClipboardItem>> = _items

    /**
     * Samples the current clip. Safe to call often: an unchanged clip is
     * recognized and does not reorder the list or touch disk.
     */
    fun capture(manager: ClipboardManager?): Boolean {
        val clip = runCatching { manager?.primaryClip }.getOrNull() ?: return false
        if (clip.itemCount <= 0) return false
        val item = clip.getItemAt(0)
        val uri = item.uri
        val text = item.text?.toString().orEmpty()

        // When the clip was copied, which is not when we got to look at it. An
        // IME may only read the clipboard while it is focused, so sampling time
        // means "whenever the keyboard last opened" — and stamping that onto a
        // clip re-dated whatever had been copied longest ago to now on every
        // single activation, parking it at the top of the list and sinking
        // every screenshot taken since underneath it.
        val copiedAt = runCatching { clip.description?.timestamp }.getOrNull()?.takeIf { it > 0L }

        val candidate = when {
            uri != null && isImage(uri) -> ClipboardItem(
                id = uri.toString(),
                text = text.ifBlank { uri.lastPathSegment.orEmpty() },
                imageUri = uri.toString(),
                copiedAt = copiedAt ?: System.currentTimeMillis()
            )
            text.isNotBlank() -> ClipboardItem(
                id = text.hashCode().toString(),
                text = text,
                copiedAt = copiedAt ?: System.currentTimeMillis()
            )
            else -> return false
        }
        return add(candidate, platformTimestamped = copiedAt != null)
    }

    fun togglePin(id: String) {
        val updated = _items.value.map { if (it.id == id) it.copy(pinned = !it.pinned) else it }
        persist(sorted(updated))
    }

    fun remove(id: String) {
        persist(_items.value.filterNot { it.id == id })
    }

    /** Pinned entries survive, matching what the Notes list does with pins. */
    fun clearUnpinned() {
        persist(_items.value.filter(ClipboardItem::pinned))
    }

    /**
     * @param platformTimestamped whether [ClipboardItem.copiedAt] is Android's
     * own copy time rather than the moment we sampled. With a real time, seeing
     * the same clip again simply produces the same timestamp and is ignored.
     * Without one, a clip we already hold is left untouched instead — re-dating
     * it to now is exactly the reordering this is here to prevent, and being
     * unable to bump a genuine re-copy back to the top is the smaller error.
     */
    private fun add(candidate: ClipboardItem, platformTimestamped: Boolean): Boolean {
        val existing = _items.value.firstOrNull { it.id == candidate.id }
        if (existing != null && (!platformTimestamped || existing.copiedAt >= candidate.copiedAt)) return false
        val merged = listOf(candidate.copy(pinned = existing?.pinned == true)) +
            _items.value.filterNot { it.id == candidate.id }
        persist(sorted(merged))
        return true
    }

    private fun sorted(values: List<ClipboardItem>) = values
        .sortedWith(compareByDescending<ClipboardItem> { it.pinned }.thenByDescending { it.copiedAt })

    /**
     * Pinned entries are kept beyond the cap so a deliberately kept snippet
     * cannot be pushed out by ordinary copying.
     */
    private fun persist(values: List<ClipboardItem>) {
        val pinned = values.filter(ClipboardItem::pinned)
        val recent = values.filterNot(ClipboardItem::pinned).take(MAX_ITEMS)
        val capped = sorted(pinned + recent)
        _items.value = capped
        val array = JSONArray()
        capped.forEach { item ->
            array.put(
                JSONObject()
                    .put("id", item.id)
                    .put("text", item.text)
                    .put("imageUri", item.imageUri ?: JSONObject.NULL)
                    .put("copiedAt", item.copiedAt)
                    .put("pinned", item.pinned)
            )
        }
        preferences.edit().putString("items", array.toString()).apply()
    }

    private fun load(): List<ClipboardItem> {
        val raw = preferences.getString("items", null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val json = array.optJSONObject(index) ?: return@mapNotNull null
                ClipboardItem(
                    id = json.optString("id"),
                    text = json.optString("text"),
                    imageUri = json.optString("imageUri").takeIf { it.isNotBlank() && it != "null" },
                    copiedAt = json.optLong("copiedAt"),
                    pinned = json.optBoolean("pinned")
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun isImage(uri: Uri) = uri.toString().let {
        it.startsWith("content://media") || it.contains("images", ignoreCase = true)
    }

    companion object {
        /** How many unpinned entries the panel keeps, clips and screenshots together. */
        const val MAX_ITEMS = 20
    }
}
