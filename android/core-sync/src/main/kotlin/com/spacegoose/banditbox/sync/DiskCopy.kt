package com.spacegoose.banditbox.sync

import com.spacegoose.banditbox.layout.LayoutDocument
import com.spacegoose.banditbox.layout.LayoutStore

/**
 * Remembers what the open profile looked like on disk when the launcher last read or wrote it,
 * so a sync that lands while the user is editing loses neither side: the user's edits are
 * replayed on top of the synced layout. Call everything while holding the store lock.
 */
class DiskCopy {
    private var base: LayoutDocument? = null

    /** [doc] was just read from disk. */
    fun loaded(doc: LayoutDocument) {
        base = doc
    }

    /** Saves [latest], with anything sync wrote since [loaded] kept. Does nothing if disk already has it. */
    fun save(store: LayoutStore, latest: LayoutDocument) {
        val onDisk = store.load(latest.profileId)
        val b = base?.takeIf { it.profileId == latest.profileId }
        val toSave = if (b != null && onDisk != null && onDisk != b) mergeEdit(onDisk, b, latest) else latest
        if (toSave != onDisk) store.save(toSave)
        base = toSave
    }

    /**
     * Sync changed [shown]'s profile on disk. Returns what to show now: the disk copy with any
     * edits not saved yet replayed on top, or null when the profile is gone.
     */
    fun reload(store: LayoutStore, shown: LayoutDocument): LayoutDocument? {
        val onDisk = store.load(shown.profileId) ?: return null
        val b = base?.takeIf { it.profileId == shown.profileId }
        base = onDisk
        return if (b != null && shown != b) mergeEdit(onDisk, b, shown) else onDisk
    }
}
