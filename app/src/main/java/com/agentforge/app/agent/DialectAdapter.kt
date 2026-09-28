package com.agentforge.app.agent

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.util.Locale

object DialectAdapter {

    fun applyRealisticGirlVoice(context: Context, tts: TextToSpeech?, text: String) {
        if (tts == null) return
        val lower = text.lowercase()
        val isBengali = lower.contains("kemon") || lower.contains("aacho") || lower.contains("bhalo") || lower.contains("korbo")

        val targetLocale = if (isBengali) Locale("bn", "IN") else Locale("hi", "IN")

        try {
            tts.language = targetLocale
            // Realistic sweet female pitch & pace preset
            tts.setPitch(1.22f)
            tts.setSpeechRate(1.02f)

            val voices = tts.voices
            if (!voices.isNullOrEmpty()) {
                // Priority scan for Google Speech Services high quality female voices
                val bestFemaleVoice: Voice? = voices.firstOrNull { voice ->
                    val name = voice.name.lowercase()
                    (name.contains("hi-in-x-hie") || name.contains("hi-in-x-hia") || 
                     name.contains("female") || name.contains("#female") || 
                     name.contains("en-in-x-end")) && !voice.isNetworkConnectionRequired
                } ?: voices.firstOrNull { voice ->
                    val name = voice.name.lowercase()
                    (name.contains("female") || name.contains("#female"))
                } ?: voices.firstOrNull { it.locale.language == targetLocale.language }

                if (bestFemaleVoice != null) {
                    tts.voice = bestFemaleVoice
                }
            }
        } catch (_: Exception) {}
    }
}
