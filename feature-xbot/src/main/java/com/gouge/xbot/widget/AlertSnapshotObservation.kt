package com.gouge.xbot.widget

import com.gouge.xbot.data.SessionStore

/** Identity travels with the payload across dispatcher buffers, including logout observations. */
data class AlertSnapshotObservation(val scope: String?, val generation: Long, val snapshot: AlertSnapshot?) {
    /** Validate and publish under one account monitor so a queued old account cannot reach UI. */
    fun withCurrentSession(session: SessionStore, action: () -> Unit): Boolean {
        var applied = false
        session.withCurrentGeneration(generation) {
            if (session.currentScope() == scope) {
                action()
                applied = true
            }
        }
        return applied
    }
}
