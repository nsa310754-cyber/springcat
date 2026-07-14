package com.springcat.monitor

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Process-wide bridge between [MonitorService] (which samples continuously, even in the
 * background) and any UI that wants to display the results.
 *
 * The service pushes each [Snapshot] here; the Activity observes. A small history buffer lets
 * the chart be reseeded when the Activity is reopened after being closed.
 */
object MonitorState {

    /** A single charted point: CPU% and memory%. */
    data class Point(val cpu: Float, val mem: Float)

    @Volatile
    var latest: Snapshot? = null
        private set

    @Volatile
    var running: Boolean = false

    private const val HISTORY_CAPACITY = 60
    private val history = ArrayDeque<Point>(HISTORY_CAPACITY)

    private val listeners = CopyOnWriteArrayList<(Snapshot) -> Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Called by the service on each sample. Safe to call from any thread. */
    fun update(snapshot: Snapshot) {
        latest = snapshot
        synchronized(history) {
            if (history.size >= HISTORY_CAPACITY) history.removeFirst()
            history.addLast(Point(snapshot.cpuPercent ?: 0f, snapshot.memPercent))
        }
        listeners.forEach { listener -> mainHandler.post { listener(snapshot) } }
    }

    fun historySnapshot(): List<Point> = synchronized(history) { history.toList() }

    fun addListener(listener: (Snapshot) -> Unit) = listeners.add(listener)

    fun removeListener(listener: (Snapshot) -> Unit) = listeners.remove(listener)
}
