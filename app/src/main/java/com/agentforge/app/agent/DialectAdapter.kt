package com.agentforge.app.agent

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.util.Locale
import kotlin.math.sin

object DialectAdapter {

    fun applyRealisticGirlVoice(context: Context, tts: TextToSpeech?, text: String) {
        if (tts == null) return
        val lower = text.lowercase()
        val isBengali = lower.contains("kemon") || lower.contains("aacho") || lower.contains("bhalo") || lower.contains("korbo")
        val targetLocale = if (isBengali) Locale("bn", "IN") else Locale("hi", "IN")

        try {
            tts.language = targetLocale
            tts.setPitch(1.22f)
            tts.setSpeechRate(1.02f)

            val voices = tts.voices
            if (!voices.isNullOrEmpty()) {
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

            // Play procedural natural breath / micro-giggle sound cue based on emotion
            playProceduralEmotionalCue(lower)
        } catch (_: Exception) {}
    }

    private fun playProceduralEmotionalCue(lower: String) {
        try {
            if (lower.contains("hahaha") || lower.contains("pagal") || lower.contains("masti")) {
                generateToneCue(freq = 640f, durationMs = 120, harmonics = true)
            } else if (lower.contains("so jao") || lower.contains("uff") || lower.contains("chinta")) {
                generateToneCue(freq = 320f, durationMs = 180, harmonics = false)
            } else if (lower.contains("shona") || lower.contains("jaanu") || lower.contains("pyaar")) {
                generateToneCue(freq = 520f, durationMs = 90, harmonics = true)
            }
        } catch (_: Exception) {}
    }

    private fun generateToneCue(freq: Float, durationMs: Int, harmonics: Boolean) {
        Thread {
            try {
                val sampleRate = 22050
                val numSamples = (sampleRate * (durationMs / 1000.0)).toInt()
                val buffer = ShortArray(numSamples)
                for (i in 0 until numSamples) {
                    val time = i.toDouble() / sampleRate
                    val envelope = sin(Math.PI * i / numSamples)
                    var wave = sin(2.0 * Math.PI * freq * time)
                    if (harmonics) {
                        wave += 0.3 * sin(2.0 * Math.PI * (freq * 1.5) * time)
                    }
                    buffer[i] = (wave * envelope * 12000).toInt().toShort()
                }

                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(buffer.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()

                track.write(buffer, 0, buffer.size)
                track.play()
                Thread.sleep(durationMs.toLong() + 50)
                track.release()
            } catch (_: Exception) {}
        }.start()
    }
}
