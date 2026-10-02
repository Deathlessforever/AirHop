package com.team.vocalink.alert

import android.app.AlertDialog
import android.content.Context
import android.view.LayoutInflater
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView

object SituationalTriageDialog {

    fun show(
        context: Context,
        onReportGenerated: (reportText: String, isSos: Boolean) -> Unit
    ) {
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 32)
            setBackgroundColor(android.graphics.Color.parseColor("#0F141C"))
        }

        fun createLabel(text: String): TextView {
            return TextView(context).apply {
                this.text = text
                setTextColor(android.graphics.Color.parseColor("#00E5FF"))
                textSize = 11f
                typeface = android.graphics.Typeface.MONOSPACE
                setPadding(0, 16, 0, 8)
            }
        }

        // 1. Disaster Type
        layout.addView(createLabel("1. DISASTER SCENARIO:"))
        val spDisaster = Spinner(context)
        val disasterOptions = arrayOf(
            "🌊 Flood Rising Water",
            "🏚️ Earthquake / Building Collapse",
            "🏢 Trapped in Debris / Rubble",
            "🩺 Medical Critical Patient",
            "⚡ Cyclone / Severe Storm"
        )
        spDisaster.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, disasterOptions)
        layout.addView(spDisaster)

        // 2. Trapped Victims Count
        layout.addView(createLabel("2. PEOPLE TRAPPED / IN DANGER:"))
        val spPeople = Spinner(context)
        val peopleOptions = arrayOf(
            "1 Person",
            "2 - 4 People",
            "5 - 10 People",
            "10+ People (Elderly & Children)"
        )
        spPeople.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, peopleOptions)
        layout.addView(spPeople)

        // 3. Water / Hazard Level
        layout.addView(createLabel("3. WATER / HAZARD LEVEL:"))
        val spHazard = Spinner(context)
        val hazardOptions = arrayOf(
            "Roof / Highest Ground (Surrounded)",
            "Waist Deep Water (3 - 5 ft)",
            "Ground Floor Submerged",
            "Debris / Pinned Under Rubble"
        )
        spHazard.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, hazardOptions)
        layout.addView(spHazard)

        // 4. Critical Need
        layout.addView(createLabel("4. IMMEDIATE RESCUE NEED:"))
        val spNeed = Spinner(context)
        val needOptions = arrayOf(
            "🚤 Rescue Boat Required Urgently",
            "🩹 Emergency First Aid & Stretcher",
            "💧 Drinking Water & Rations",
            "🚁 Evacuation Team Needed"
        )
        spNeed.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, needOptions)
        layout.addView(spNeed)

        AlertDialog.Builder(context)
            .setTitle("📋 Quick Situational Triage Report")
            .setView(layout)
            .setPositiveButton("BROADCAST SOS REPORT") { _, _ ->
                val disaster = spDisaster.selectedItem.toString().substringAfter(" ")
                val people = spPeople.selectedItem.toString()
                val hazard = spHazard.selectedItem.toString()
                val need = spNeed.selectedItem.toString().substringAfter(" ")

                val report = "🚨 [TRIAGE SOS] $disaster | $people | Level: $hazard | NEED: $need"
                onReportGenerated(report, true)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}