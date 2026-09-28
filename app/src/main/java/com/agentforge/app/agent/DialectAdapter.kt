package com.agentforge.app.agent

import java.util.Locale

enum class DialectMode {
    BENGALI_CASUAL,
    HINDI_SLANG_CASUAL,
    HINGLISH_WARM,
    ENGLISH_TECH_PRECISE
}

data class DialectProfile(
    val mode: DialectMode,
    val systemPromptInstructions: String,
    val ttsLocale: Locale,
    val ttsPitch: Float,
    val ttsSpeechRate: Float
)

object DialectAdapter {

    fun detectDialect(input: String): DialectProfile {
        val lower = input.lowercase(Locale.getDefault())

        val bengaliMarkers = listOf(
            "kemon", "achho", "achis", "bhalo", "dorkar", "korchhi", "koro", "shono", 
            "ki korcho", "ekta", "kaj", "bhai re", "bolchi", "dada", "hobe", "parbo"
        )
        val hindiSlangMarkers = listOf(
            "bhai", "yaar", "abe", "scene", "jugaad", "chal", "apna", "apun", "mast", 
            "load mat le", "fatafat", "bata na", "kya chal raha", "bawal", "kadak"
        )

        val bengaliScore = bengaliMarkers.count { lower.contains(it) }
        val hindiSlangScore = hindiSlangMarkers.count { lower.contains(it) }

        return when {
            bengaliScore > 0 && bengaliScore >= hindiSlangScore -> {
                DialectProfile(
                    mode = DialectMode.BENGALI_CASUAL,
                    systemPromptInstructions = "Tone: Casual, friendly Bengali mixed with colloquial touches (Bangla/Banglish). Speak like a supportive, witty local companion from Kolkata/Bengal. Mirror the user's regional phrases naturally.",
                    ttsLocale = Locale("bn", "IN"),
                    ttsPitch = 1.05f,
                    ttsSpeechRate = 1.0f
                )
            }
            hindiSlangScore > 0 -> {
                DialectProfile(
                    mode = DialectMode.HINDI_SLANG_CASUAL,
                    systemPromptInstructions = "Tone: Super relatable, street-smart casual Hinglish. Use natural urban buddy slang ('bhai', 'scene', 'sorted hai', 'tension mat le'). Keep responses lively, punchy, and empathetic.",
                    ttsLocale = Locale("hi", "IN"),
                    ttsPitch = 0.98f,
                    ttsSpeechRate = 1.08f
                )
            }
            lower.contains("karo") || lower.contains("hai") || lower.contains("kya") || lower.contains("kaise") -> {
                DialectProfile(
                    mode = DialectMode.HINGLISH_WARM,
                    systemPromptInstructions = "Tone: Balanced, warm Hinglish. Smart assistant who is approachable, proactive, and clear.",
                    ttsLocale = Locale("hi", "IN"),
                    ttsPitch = 1.0f,
                    ttsSpeechRate = 1.02f
                )
            }
            else -> {
                DialectProfile(
                    mode = DialectMode.ENGLISH_TECH_PRECISE,
                    systemPromptInstructions = "Tone: Crisp, modern Jarvis-style English. Direct, efficient, zero filler.",
                    ttsLocale = Locale.US,
                    ttsPitch = 1.0f,
                    ttsSpeechRate = 1.05f
                )
            }
        }
    }
}
