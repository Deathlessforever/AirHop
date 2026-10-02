package com.team.vocalink.alert

import android.app.AlertDialog
import android.content.Context
import android.widget.ScrollView
import android.widget.TextView

object EmergencySurvivalGuide {

    fun showGuideDialog(context: Context) {
        val sb = StringBuilder()
        sb.append("========================================\n")
        sb.append("AIRHOP OFFLINE FIRST-AID & SURVIVAL MANUAL\n")
        sb.append("(No Internet Required • Floods & Earthquakes)\n")
        sb.append("========================================\n\n")

        sb.append("1. DROWNING & WATER INHALATION (CPR)\n")
        sb.append("----------------------------------------\n")
        sb.append("• Lay victim on back on a flat, dry surface.\n")
        sb.append("• Clear mouth of mud, weeds, or vomit.\n")
        sb.append("• Tilt head back slightly and lift chin to open airway.\n")
        sb.append("• If NOT breathing: Give 2 rescue breaths (pinch nose, seal lips over mouth).\n")
        sb.append("• CHEST COMPRESSIONS: Place heels of both hands in center of breastbone.\n")
        sb.append("• Push hard & fast: 30 compressions (100-120 per minute, 2 inches deep).\n")
        sb.append("• Cycle: 30 compressions followed by 2 rescue breaths.\n")
        sb.append("• Do NOT stop until victim coughs, moves, or medics arrive.\n\n")

        sb.append("2. SEVERE BLEEDING & WOUNDS\n")
        sb.append("----------------------------------------\n")
        sb.append("• Apply FIRM, DIRECT PRESSURE using a clean cloth, bandage, or shirt.\n")
        sb.append("• Maintain pressure without lifting cloth for at least 10 minutes.\n")
        sb.append("• Elevate the injured arm or leg above heart level.\n")
        sb.append("• ARTERIAL BLEEDING (Spurting bright red blood):\n")
        sb.append("  - Tie a cloth 2-3 inches ABOVE the wound (not over a joint).\n")
        sb.append("  - Use a stick or pen to twist the cloth until bleeding stops completely.\n")
        sb.append("  - Note the time on forehead so medics know tourniquet duration.\n\n")

        sb.append("3. SNAKEBITE EMERGENCY (COMMON IN FLOODS)\n")
        sb.append("----------------------------------------\n")
        sb.append("• Keep victim CALM and completely still (running pumps venom to heart).\n")
        sb.append("• Keep bitten limb IMMOBILIZED and positioned BELOW the heart level.\n")
        sb.append("• Remove rings, watches, and shoes immediately before swelling starts.\n")
        sb.append("• DO NOT cut the bite area.\n")
        sb.append("• DO NOT suck venom with mouth.\n")
        sb.append("• DO NOT apply ice or electric shock.\n")
        sb.append("• Wrap firmly from fingers/toes up toward body (like a sprain wrap).\n\n")

        sb.append("4. DRINKING WATER PURIFICATION IN FLOODS\n")
        sb.append("----------------------------------------\n")
        sb.append("• Floodwater carries sewage, cholera, typhus, and leptospirosis. NEVER drink raw.\n")
        sb.append("• STEP 1 (Clarification): Let water sit in container for 1 hour, or filter through 4 layers of clean cotton cloth.\n")
        sb.append("• STEP 2 (Disinfection):\n")
        sb.append("  - BOILING: Boil vigorously with rolling bubbles for at least 1 full minute.\n")
        sb.append("  - CHLORINE/BLEACH: 2 drops unscented household bleach per 1 Liter clear water. Wait 30 mins.\n")
        sb.append("  - SOLAR SODIS: Put water in clear plastic bottles on roof in direct sun for 6 hours.\n\n")

        sb.append("5. EARTHQUAKE & COLLAPSE SURVIVAL\n")
        sb.append("----------------------------------------\n")
        sb.append("• DROP to hands and knees.\n")
        sb.append("• COVER head and neck under sturdy desk, table, or bed.\n")
        sb.append("• HOLD ON until shaking stops.\n")
        sb.append("• IF TRAPPED UNDER RUBBLE:\n")
        sb.append("  - Cover nose and mouth with cloth to prevent dust inhalation.\n")
        sb.append("  - Do NOT shout continuously (causes dust inhalation & voice exhaustion).\n")
        sb.append("  - TAP rhythmically on pipes or walls with a stone/metal object (acoustic probes detect this).\n")
        sb.append("  - Use AirHop SOS siren or screen light when you hear footsteps.\n\n")

        sb.append("6. ELECTRICAL HAZARDS & SUBMERGED POWER LINES\n")
        sb.append("----------------------------------------\n")
        sb.append("• Stay at least 30 feet away from downed electric poles touching water.\n")
        sb.append("• Water conducts high voltage! If you feel tingling in water, TURN BACK immediately.\n")
        sb.append("• Turn off main home circuit breaker if water starts entering the house.\n")

        val textView = TextView(context).apply {
            text = sb.toString()
            textSize = 12f
            setPadding(32, 24, 32, 24)
            setTextColor(android.graphics.Color.WHITE)
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
        }

        val scrollView = ScrollView(context).apply {
            addView(textView)
            setBackgroundColor(android.graphics.Color.parseColor("#0F141C"))
        }

        AlertDialog.Builder(context)
            .setTitle("Offline Emergency Survival Guide")
            .setView(scrollView)
            .setPositiveButton("I UNDERSTAND", null)
            .show()
    }
}