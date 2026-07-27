package com.jointheparty.app

import android.app.Application
import com.jointheparty.app.session.SessionGraph

/**
 * INT-06a (technical-requirements.md §2.5): anchors the process-scoped
 * [SessionGraph] above any single Activity's lifecycle, so a rotation or
 * other Activity recreation reattaches to the live session instead of
 * rebuilding it. [sessionGraph] is lazy — nothing is built (no SyncCore
 * handle, no App Remote connection, no route observer) until the first
 * access, which in practice is [com.jointheparty.app.MainActivity]'s
 * `viewModel` property.
 */
class JoinThePartyApplication : Application() {
    val sessionGraph: SessionGraph by lazy { SessionGraph(this) }
}
