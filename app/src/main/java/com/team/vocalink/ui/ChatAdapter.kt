package com.team.vocalink.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.team.vocalink.R
import com.team.vocalink.core.ChatMessage
import com.team.vocalink.core.MessageStatus
import com.team.vocalink.core.ProtocolConstants
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ChatAdapter(
    private val onSpeakClicked: (ChatMessage) -> Unit,
    private val onNavigateClicked: (ChatMessage) -> Unit
) : ListAdapter<ChatMessage, RecyclerView.ViewHolder>(DiffCallback) {

    var currentLat: Double = ProtocolConstants.BENCHMARK_MYSURU_LAT
    var currentLon: Double = ProtocolConstants.BENCHMARK_MYSURU_LON

    companion object {
        private const val VIEW_TYPE_OUTGOING = 1
        private const val VIEW_TYPE_INCOMING = 2

        private val DiffCallback = object : DiffUtil.ItemCallback<ChatMessage>() {
            override fun areItemsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean =
                oldItem == newItem
        }
    }

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    override fun getItemViewType(position: Int): Int {
        return if (getItem(position).isFromMe) VIEW_TYPE_OUTGOING else VIEW_TYPE_INCOMING
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_TYPE_OUTGOING) {
            val v = inflater.inflate(R.layout.item_chat_outgoing, parent, false)
            OutgoingViewHolder(v)
        } else {
            val v = inflater.inflate(R.layout.item_chat_incoming, parent, false)
            IncomingViewHolder(v)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = getItem(position)
        if (holder is OutgoingViewHolder) {
            holder.bind(item)
        } else if (holder is IncomingViewHolder) {
            holder.bind(item, onSpeakClicked, onNavigateClicked)
        }
    }

    inner class OutgoingViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvText: TextView = itemView.findViewById(R.id.tvOutgoingText)
        private val tvMeta: TextView = itemView.findViewById(R.id.tvOutgoingMeta)
        private val tvTick: TextView = itemView.findViewById(R.id.tvDeliveryTick)

        fun bind(msg: ChatMessage) {
            tvText.text = msg.text
            val timeStr = timeFormat.format(Date(msg.timestamp))

            if (msg.status == MessageStatus.DELIVERED) {
                // THE BLUE TICK (Delivered to peer)
                tvTick.text = "✓✓"
                tvTick.setTextColor(Color.parseColor("#00E5FF")) // Electric Blue
                val lat = if (msg.latencyMs != null) " • ${msg.latencyMs}ms" else ""
                tvMeta.text = "$timeStr • Delivered$lat"
            } else {
                // SINGLE GREY TICK (Sent / In Flight)
                tvTick.text = "✓"
                tvTick.setTextColor(Color.parseColor("#8B949E")) // Grey
                tvMeta.text = "$timeStr • Sent via BLE (40B)"
            }
        }
    }

    inner class IncomingViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvSender: TextView = itemView.findViewById(R.id.tvIncomingSender)
        private val tvDistance: TextView = itemView.findViewById(R.id.tvIncomingDistance)
        private val tvSurvivorLocation: TextView = itemView.findViewById(R.id.tvSurvivorLocation)
        private val tvText: TextView = itemView.findViewById(R.id.tvIncomingText)
        private val tvMeta: TextView = itemView.findViewById(R.id.tvIncomingMeta)
        private val btnSpeak: Button = itemView.findViewById(R.id.btnReplayAudio)
        private val btnNavigate: Button = itemView.findViewById(R.id.btnNavigateToSurvivor)

        fun bind(
            msg: ChatMessage,
            speakCallback: (ChatMessage) -> Unit,
            navigateCallback: (ChatMessage) -> Unit
        ) {
            tvSender.text = msg.senderName
            tvText.text = msg.text
            val timeStr = timeFormat.format(Date(msg.timestamp))
            tvMeta.text = "$timeStr • Hop ${msg.hopCount}"
            tvDistance.text = "Relay Hop ${msg.hopCount}"

            // Real-time Geodesic Distance & Cardinal Bearing Calculation
            val results = FloatArray(2)
            android.location.Location.distanceBetween(currentLat, currentLon, msg.lat, msg.lon, results)
            val distMeters = results[0]
            val bearing = (results[1] + 360) % 360
            val cardinal = when {
                bearing >= 337.5 || bearing < 22.5 -> "North"
                bearing < 67.5 -> "North-East"
                bearing < 112.5 -> "East"
                bearing < 157.5 -> "South-East"
                bearing < 202.5 -> "South"
                bearing < 247.5 -> "South-West"
                bearing < 292.5 -> "West"
                else -> "North-West"
            }
            val distStr = if (distMeters < 1000) "${distMeters.toInt()}m" else String.format(Locale.US, "%.1fkm", distMeters / 1000f)
            tvSurvivorLocation.text = "📍 $distStr away • $cardinal [${String.format(Locale.US, "%.4f", msg.lat)}°N, ${String.format(Locale.US, "%.4f", msg.lon)}°E]"

            btnSpeak.setOnClickListener {
                speakCallback(msg)
            }

            btnNavigate.setOnClickListener {
                navigateCallback(msg)
            }
        }
    }
}
