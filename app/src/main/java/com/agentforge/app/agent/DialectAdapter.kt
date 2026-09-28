package com.agentforge.app.agent

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import com.agentforge.app.data.AppPrefs
import java.util.Locale

object DialectAdapter {

    fun applyRealisticGirlVoice(context: Context, tts: TextToSpeech?, text: String) {
        if (tts == null) return
        val prefs = AppPrefs(context)
        val lower = text.lowercase()
        val isBengali = lower.contains("kemon") || lower.contains("aacho") || lower.contains("bhalo") || lower.contains("korbo")

        val targetLocale = if (isBengali) Locale("bn", "IN") else Locale("hi", "IN")

        try {
            tts.language = targetLocale
            tts.setPitch(prefs.customVoicePitch)
            tts.setSpeechRate(prefs.customVoiceSpeed)

            val voices = tts.voices
            if (!voices.isNullOrEmpty()) {
                val chosenVoiceName = prefs.selectedVoiceName
                var matchedVoice: Voice? = null

                if (chosenVoiceName != "default_female") {
                    matchedVoice = voices.firstOrNull { it.name.equals(chosenVoiceName, ignoreCase = true) }
                }

                if (matchedVoice == null) {
                    matchedVoice = voices.firstOrNull { voice ->
                        val name = voice.name.lowercase()
                        (name.contains("female") || name.contains("#female") || name.contains("hi-in-x-hie") || name.contains("en-in-x-end")) &&
                                !voice.isNetworkConnectionRequired
                    } ?: voices.firstOrNull { it.locale.language == targetLocale.language && it.name.lowercase().contains("female") }
                }

                if (matchedVoice != null) {
                    tts.voice = matchedVoice
                }
            }
        } catch (_: Exception) {}
    }
}
