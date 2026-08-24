package com.example.meshrelay

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The command-centre view: what a dispatcher actually looks at.
 *
 * Sorted by urgency, not by arrival. That ordering is the whole point and it is
 * invisible in a scrolling log, which is why this exists (Plan.md 17.2).
 */
class MessageAdapter : RecyclerView.Adapter<MessageAdapter.Row>() {

    private var items: List<MeshMessage> = emptyList()
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun submit(list: List<MeshMessage>) {
        items = list
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    class Row(view: View) : RecyclerView.ViewHolder(view) {
        val stripe: View = view.findViewById(R.id.vStripe)
        val head: TextView = view.findViewById(R.id.tvHead)
        val body: TextView = view.findViewById(R.id.tvBody)
        val meta: TextView = view.findViewById(R.id.tvMeta)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Row(
        LayoutInflater.from(parent.context).inflate(R.layout.item_message, parent, false)
    )

    override fun onBindViewHolder(holder: Row, position: Int) {
        val m = items[position]

        holder.stripe.setBackgroundColor(colourFor(m.priority))

        // A verified order is marked as one. An unsigned order never reaches this list
        // at all - it is refused before it is stored.
        val badge = if (m.type.needsSignature) "  [VERIFIED ORDER]" else ""
        holder.head.text = m.type.label.uppercase() + badge
        holder.head.setTextColor(colourFor(m.priority))

        holder.body.text = m.text

        // Shown because the hidden rules have to be visible to count for anything:
        // how far it has left to travel, how many copies it may still spend, and the
        // phones it physically passed through to get here.
        holder.meta.text = clock.format(Date(m.createdAt)) +
            "   ttl " + m.ttl +
            "   copies " + m.copies +
            "\nvia " + m.path.joinToString(" > ") { it.take(4) }
    }

    private fun colourFor(priority: Int) = when {
        priority >= 9 -> Color.parseColor("#D32F2F")   // medical, missing, orders
        priority >= 8 -> Color.parseColor("#F57C00")   // fire, security
        priority >= 5 -> Color.parseColor("#FBC02D")   // crowding
        else -> Color.parseColor("#78909C")            // chatter
    }
}
