package com.example.meshrelay

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
    private var own: Position? = null
    private var myNodeId: String = ""
    private var deliveryOf: (String) -> Delivery? = { null }
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

    /** Everything this list has already shown, so only genuine arrivals animate. */
    private val known = mutableSetOf<String>()

    /**
     * Arrivals waiting to be animated. Consumed on first bind, so scrolling a message
     * off screen and back does not replay its entrance.
     */
    private val arriving = mutableSetOf<String>()

    /** Receipts already celebrated. The flip to DELIVERED happens once, not on every repaint. */
    private val confirmed = mutableSetOf<String>()

    fun submit(
        list: List<MeshMessage>,
        ownPosition: Position? = null,
        myNode: String = "",
        delivery: (String) -> Delivery? = { null }
    ) {
        // A message this list has never seen has just crossed the crowd to get here.
        // Worth a quarter of a second, especially when a phone comes back into range and
        // a whole held-up batch lands at once - that flush is store-and-forward, the
        // least obvious behaviour in the project and the one worth making people watch.
        for (m in list) if (known.add(m.id)) arriving += m.id

        items = list
        own = ownPosition
        myNodeId = myNode
        deliveryOf = delivery
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    class Row(view: View) : RecyclerView.ViewHolder(view) {
        val stripe: View = view.findViewById(R.id.vStripe)
        val tag: TextView = view.findViewById(R.id.tvTag)
        val verified: TextView = view.findViewById(R.id.tvVerified)
        val where: TextView = view.findViewById(R.id.tvWhere)
        val body: TextView = view.findViewById(R.id.tvBody)
        val meta: TextView = view.findViewById(R.id.tvMeta)
        val receipt: TextView = view.findViewById(R.id.tvReceipt)
        val density: Float = view.resources.displayMetrics.density
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Row(
        LayoutInflater.from(parent.context).inflate(R.layout.item_message, parent, false)
    )

    override fun onBindViewHolder(holder: Row, position: Int) {
        val m = items[position]
        val colour = Palette.forPriority(m.priority)
        val dp = holder.density

        // The stripe is the first thing read and often the only thing read.
        holder.stripe.background = Palette.pill(colour, 2f * dp)

        // The type as a filled pill. A tinted fill rather than a solid one: seven
        // saturated blocks in a list is noise, and the stripe already carries the
        // urgency. The pill only has to name the thing.
        holder.tag.text = m.type.tag
        holder.tag.setTextColor(colour)
        holder.tag.background = Palette.pill(
            Palette.tint(colour, 38), 6f * dp, Palette.tint(colour, 90), (1 * dp).toInt()
        )

        // A verified order says so. An unsigned one never reaches this list at all -
        // it is refused before it is stored, and it dies at the first honest phone.
        val signed = m.type.needsSignature
        holder.verified.visibility = if (signed) View.VISIBLE else View.GONE
        if (signed) {
            holder.verified.setTextColor(Palette.TEAL)
            holder.verified.background = Palette.pill(
                Palette.tint(Palette.TEAL, 38), 6f * dp,
                Palette.tint(Palette.TEAL, 90), (1 * dp).toInt()
            )
        }

        // Distance is what a dispatcher can act on. Raw coordinates are not.
        val here = own
        val there = m.pos
        holder.where.text = when {
            there != null && here != null -> describeDistance(Position.metresBetween(here, there))
            there != null -> there.encode()
            m.place != null -> m.place          // typed, when GPS could not be had
            else -> ""
        }

        holder.body.text = m.text

        // Entrance, once. The reset in the else branch matters as much as the animation:
        // RecyclerView hands back used views, and one left half faded would look like a
        // rendering bug at exactly the wrong moment.
        val row = holder.itemView
        row.animate().cancel()
        if (arriving.remove(m.id)) {
            row.alpha = 0f
            row.translationY = -10f * dp
            row.animate().alpha(1f).translationY(0f).setDuration(260).start()
        } else {
            row.alpha = 1f
            row.translationY = 0f
        }

        // Shown because a rule the judges cannot see does not exist: how far it has
        // left to travel, how many copies it may still spend, and the phones it
        // physically passed through to get here.
        holder.meta.text = clock.format(Date(m.createdAt)) +
            "   ttl " + m.ttl +
            "   copies " + m.copies +
            "\nvia " + m.path.joinToString(" > ") { it.take(4) }

        showDeliveryState(holder, m)
    }

    /**
     * Whether the report you sent got anywhere - shown only on your own reports, because
     * it is only your own that you are waiting on. Everything else in this list arrived,
     * by definition, so there is nothing to say about it.
     *
     * A message with no delivery record is one nobody promised to confirm: chatter and
     * crowding reports, which are not worth a second message travelling back through the
     * crowd. Those show nothing at all rather than a permanent "in flight" that would
     * read as a failure.
     */
    private fun showDeliveryState(holder: Row, m: MeshMessage) {
        val d = if (m.origin == myNodeId) deliveryOf(m.id) else null
        if (d == null) {
            holder.receipt.visibility = View.GONE
            return
        }
        holder.receipt.visibility = View.VISIBLE
        val dp = holder.density

        if (d.isDelivered) {
            // Green appears in exactly one place in this app, and this is it.
            holder.receipt.text = "✓  DELIVERED  ·  " + d.hops + " hop" +
                (if (d.hops == 1) "" else "s") + "  ·  confirmed in " + d.seconds + "s" +
                "  ·  by " + (d.by ?: "?").take(4)
            holder.receipt.setTextColor(Palette.GREEN)
            holder.receipt.background = Palette.pill(
                Palette.tint(Palette.GREEN, 30), 8f * dp,
                Palette.tint(Palette.GREEN, 80), (1 * dp).toInt()
            )

            // Demo moment five: a judge is holding this phone, watching their own report
            // sit at "in flight". The flip is the payoff and it must not be something
            // they notice only because someone pointed at it afterwards.
            holder.receipt.animate().cancel()
            if (confirmed.add(m.id)) {
                holder.receipt.alpha = 0f
                holder.receipt.scaleX = 0.94f
                holder.receipt.scaleY = 0.94f
                holder.receipt.animate()
                    .alpha(1f).scaleX(1f).scaleY(1f).setDuration(320).start()
            } else {
                holder.receipt.alpha = 1f
                holder.receipt.scaleX = 1f
                holder.receipt.scaleY = 1f
            }
        } else {
            holder.receipt.animate().cancel()
            holder.receipt.alpha = 1f
            holder.receipt.scaleX = 1f
            holder.receipt.scaleY = 1f
            // Orange, not red: waiting is not failure. It is the normal state of a
            // message crossing a crowd, and it is the state a judge is watching change.
            holder.receipt.text = "○  IN FLIGHT  ·  no responder has confirmed it yet"
            holder.receipt.setTextColor(Palette.ORANGE)
            holder.receipt.background = Palette.pill(
                Palette.tint(Palette.ORANGE, 26), 8f * dp,
                Palette.tint(Palette.ORANGE, 70), (1 * dp).toInt()
            )
        }
    }
}
