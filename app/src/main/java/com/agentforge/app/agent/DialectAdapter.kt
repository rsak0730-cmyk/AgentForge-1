package com.agentforge.app.agent

import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.util.Locale

object DialectAdapter {

    data class VoiceConfig(
        val ttsLocale: Locale,
        val ttsPitch: Float,
        val ttsSpeechRate: Float
    )

    fun detectDialect(text: String): VoiceConfig {
        val lower = text.lowercase()
        val isBengali = lower.contains("kemon") || lower.contains("aacho") || lower.contains("bhalo") || lower.contains("korbo")
        
        return if (isBengali) {
            VoiceConfig(Locale("bn", "IN"), 1.15f, 0.98f)
        } else {
            // High-natural expressive young female pitch
            VoiceConfig(Locale("hi", "IN"), 1.22f, 1.02f)
        }
    }

    /**
     * Android TTS Engine me installed available female high-quality voices select karta hai
     */
    fun applyRealisticGirlVoice(tts: TextToSpeech?, text: String) {
        if (tts == null) return
        val config = detectDialect(text)
        
        try {
            tts.language = config.ttsLocale
            tts.setPitch(config.ttsPitch)
            tts.setSpeechRate(config.ttsSpeechRate)

            // Scan and attach natural female voice pack from Google TTS engine
            val voices = tts.voices
            if (!voices.isNullOrEmpty()) {
                val bestFemaleVoice: Voice? = voices.firstOrNull { voice ->
                    val name = voice.name.lowercase()
                    (name.contains("female") || name.contains("female") || name.contains("#female") || name.contains("hi-in-x-hie") || name.contains("en-in-x-end")) &&
                            !voice.isNetworkConnectionRequired
                } ?: voices.firstOrNull { it.locale.language == config.ttsLocale.language && it.name.lowercase().contains("female") }

                if (bestFemaleVoice != null) {
                    tts.voice = bestFemaleVoice
                }
            }
        } catch (_: Exception) {}
    }
}
