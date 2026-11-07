package com.noahwallet

import android.app.Activity
import android.os.Bundle

/**
 * The static launcher shortcuts' target (Receive, Scan, Send). Android launches static shortcuts with
 * FLAG_ACTIVITY_CLEAR_TASK, which would close a Send review; this passes the route on to [MainActivity] the way a
 * widget tap does, from a task of its own, so the app keeps its place and the route is gated like a widget's.
 */
class ShortcutActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent.data?.let { startActivity(com.noahwallet.ui.routeIntent(this, it)) }
        finish()
    }
}
