package com.spacegoose.banditbox.sync

import com.spacegoose.banditbox.layout.LayoutDocument
import com.spacegoose.banditbox.layout.LayoutStore
import java.util.UUID

/**
 * Turns every saved change into queued ops while the TV is signed in to a server. Install it
 * as the [LayoutStore.Listener]. Changes made before the first sync aren't queued: that sync
 * either uploads the TV's layouts or replaces them.
 */
class SyncRecorder(
    private val queue: OpQueue,
    private val state: () -> SyncState,
    private val clock: Hlc,
    private val onQueued: () -> Unit = {},
) : LayoutStore.Listener {

    private fun active() = state().let { it.signedIn && it.initialized && !it.revoked }

    override fun saved(before: LayoutDocument?, after: LayoutDocument) {
        if (!active()) return
        if (before == null) {
            queue.append(OpQueue.Entry.Create(after.profileId))
        } else {
            val ops = LayoutPaths.ops(LayoutJsonOps.toJson(before), LayoutJsonOps.toJson(after), ::newOpId, clock::next)
            if (ops.isEmpty()) return
            queue.append(OpQueue.Entry.Ops(after.profileId, ops))
        }
        onQueued()
    }

    override fun deleted(profileId: String) {
        if (!active()) return
        queue.append(OpQueue.Entry.Delete(profileId))
        onQueued()
    }

    companion object {
        fun newOpId(): String = "op_" + UUID.randomUUID().toString().replace("-", "")
    }
}
