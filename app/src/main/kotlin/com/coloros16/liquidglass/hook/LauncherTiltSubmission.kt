/* DreamGlass contributors. GPL-3.0-or-later. */
package com.coloros16.liquidglass.hook

import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Retains only poses produced by our verified target callbacks, until the real
 * native transaction is applied. Native animation code can merge transactions
 * and write another affine matrix after the target callback has returned.
 */
internal class LauncherTiltSubmission<T : Any, S : Any> {
    data class Pose<S : Any>(val surface: WeakReference<S>, val matrix: FloatArray, val created: Long)
    private val pending = WeakHashMap<T, MutableList<Pose<S>>>()

    @Synchronized fun put(transaction: T, surface: S, matrix: FloatArray, now: Long) {
        require(matrix.size == 16)
        pending.values.forEach { list -> list.removeAll { it.surface.get() == null || now - it.created > 240 } }
        pending.entries.removeAll { it.value.isEmpty() }
        if (pending.size >= 32 && !pending.containsKey(transaction)) return
        val list = pending.getOrPut(transaction) { mutableListOf() }
        list.removeAll { it.surface.get() === surface }
        if (list.size < 16) list.add(Pose(WeakReference(surface), matrix.copyOf(), now))
    }

    @Synchronized fun contains(transaction: T, surface: S): Boolean =
        pending[transaction]?.any { it.surface.get() === surface } == true

    @Synchronized fun take(transaction: T, now: Long): List<Pose<S>> =
        pending.remove(transaction)?.filter { it.surface.get() != null && now - it.created in 0..240 } ?: emptyList()

    @Synchronized fun discard(transaction: T) { pending.remove(transaction) }

    @Synchronized fun merge(receiver: T, source: T, now: Long) {
        if (receiver === source) return
        // The source transaction's last native state wins during merge.
        take(source, now).forEach { pose -> pose.surface.get()?.let { put(receiver, it, pose.matrix, pose.created) } }
    }
}
