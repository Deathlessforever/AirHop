package com.team.vocalink.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.team.vocalink.R
import com.team.vocalink.core.PacketAction
import com.team.vocalink.core.WaterfallLogItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class WaterfallAdapter : RecyclerView.Adapter<WaterfallAdapter.WaterfallViewHolder>() {

    private val items = ArrayList<WaterfallLogItem>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    fun addItem(item: WaterfallLogItem) {
        items.add(0, item)
        if (items.size > 200) {
            items.removeAt(items.size - 1)
        }
        notifyItemInserted(0)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): WaterfallViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_waterfall_log, parent, false)
        return WaterfallViewHolder(view)
    }

    override fun onBindViewHolder(holder: WaterfallViewHolder, position: Int) {
        holder.bind(items[position], timeFormat)
    }

    override fun getItemCount(): Int = items.size

    class WaterfallViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvLogTime: TextView = itemView.findViewById(R.id.tvLogTime)
        private val tvLogMsgId: TextView = itemView.findViewById(R.id.tvLogMsgId)
        private val tvLogAction: TextView = itemView.findViewById(R.id.tvLogAction)
        private val tvLogTtl: TextView = itemView.findViewById(R.id.tvLogTtl)
        private val tvLogLocation: TextView = itemView.findViewById(R.id.tvLogLocation)
        private val tvLogRsStatus: TextView = itemView.findViewById(R.id.tvLogRsStatus)
        private val tvLogTokens: TextView = itemView.findViewById(R.id.tvLogTokens)

        fun bind(item: WaterfallLogItem, timeFormat: SimpleDateFormat) {
            tvLogTime.text = timeFormat.format(Date(item.timestamp))
            tvLogMsgId.text = "ID: 0x%08X".format(item.msgId)
            tvLogTtl.text = "TTL: ${item.ttl}"

            // Action Badge styling
            tvLogAction.text = item.action.name
            when (item.action) {
                PacketAction.RELAYED -> {
                    tvLogAction.setTextColor(Color.parseColor("#00E676"))
                    tvLogAction.setBackgroundColor(Color.parseColor("#1C3829"))
                }
                PacketAction.INGESTED -> {
                    tvLogAction.setTextColor(Color.parseColor("#58A6FF"))
                    tvLogAction.setBackgroundColor(Color.parseColor("#1F2D40"))
                }
                PacketAction.REPAIRED -> {
                    tvLogAction.setTextColor(Color.parseColor("#FFD600"))
                    tvLogAction.setBackgroundColor(Color.parseColor("#3D3512"))
                }
                PacketAction.ALERT_TRIGGERED -> {
                    tvLogAction.setTextColor(Color.parseColor("#FF1744"))
                    tvLogAction.setBackgroundColor(Color.parseColor("#42151B"))
                }
                else -> {
                    tvLogAction.setTextColor(Color.parseColor("#8B949E"))
                    tvLogAction.setBackgroundColor(Color.parseColor("#21262D"))
                }
            }

            // Location & distance
            val distStr = if (item.distanceMeters != null) {
                if (item.distanceMeters < 1000.0) "${item.distanceMeters.toInt()}m"
                else "%.1fkm".format(item.distanceMeters / 1000.0)
            } else "N/A"

            val geoStr = if (item.inGeofence) " [IN GEOFENCE]" else ""
            tvLogLocation.text = "%.4f°N, %.4f°E (%s)%s".format(item.lat, item.lon, distStr, geoStr)

            // RS Parity info
            if (item.correctedBytes > 0) {
                tvLogRsStatus.text = "RS: +${item.correctedBytes}B REPAIRED"
                tvLogRsStatus.setTextColor(Color.parseColor("#FFD600"))
            } else {
                tvLogRsStatus.text = "RS(40,32): OK"
                tvLogRsStatus.setTextColor(Color.parseColor("#00E676"))
            }

            // Tokens summary
            tvLogTokens.text = if (item.isSos) {
                "!!! EMERGENCY SOS FRAME !!! // Tokens: ${item.tokensSummary}"
            } else {
                "Tokens: ${item.tokensSummary}"
            }
            if (item.isSos) {
                tvLogTokens.setTextColor(Color.parseColor("#FF5252"))
            } else {
                tvLogTokens.setTextColor(Color.parseColor("#F0F6FC"))
            }
        }
    }
}
