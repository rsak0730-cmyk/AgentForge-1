package com.agentforge.app.security

import android.content.Context
import com.agentforge.app.data.AppPrefs
import kotlin.math.abs
import kotlin.math.sqrt

class VoiceprintManager(context: Context) {
    private val prefs = AppPrefs(context)

    // Save user's acoustic biometric profile from PCM / RMS samples
    fun saveVoiceProfile(samples: List<FloatArray>) {
        if (samples.isEmpty()) return
        val featureCount = samples.first().size
        val avgVector = FloatArray(featureCount)

        for (i in 0 until featureCount) {
            var sum = 0f
            for (sample in samples) {
                sum += sample[i]
            }
            avgVector[i] = sum / samples.size
        }

        val serialized = avgVector.joinToString(",") { it.toString() }
        prefs.enrolledVoiceprint = serialized
        prefs.isVoiceprintEnrolled = true
    }

    // Verify incoming voice sample against enrolled biometric profile
    fun verifySpeaker(incomingFeatures: FloatArray, threshold: Float = 0.72f): Boolean {
        if (!prefs.isVoiceprintEnrolled) return true // Enrollment off hone par allow

        val saved = prefs.enrolledVoiceprint
        if (saved.isBlank()) return true

        val enrolledVector = saved.split(",").mapNotNull { it.toFloatOrNull() }.toFloatArray()
        if (enrolledVector.size != incomingFeatures.size) return false

        val similarity = cosineSimilarity(incomingFeatures, enrolledVector)
        return similarity >= threshold
    }

    // Extract frequency & energy vector from short audio frames
    fun extractAcousticFeatures(buffer: ByteArray, bytesRead: Int): FloatArray {
        val features = FloatArray(16)
        if (bytesRead < 32) return features

        var energy = 0f
        var zeroCrossings = 0
        var prevSample = 0

        for (i in 0 until bytesRead - 1 step 2) {
            val sample = (buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)
            energy += (sample * sample).toFloat()
            if ((sample >= 0 && prevSample < 0) || (sample < 0 && prevSample >= 0)) {
                zeroCrossings++
            }
            prevSample = sample
            val bin = (i / 2) % 16
            features[bin] += abs(sample.toFloat())
        }

        // Normalize feature vector
        val norm = sqrt(energy + 1e-6f)
        for (i in features.indices) {
            features[i] = features[i] / norm
        }
        features[0] = zeroCrossings.toFloat() / (bytesRead / 2f)
        return features
    }

    private fun cosineSimilarity(v1: FloatArray, v2: FloatArray): Float {
        var dot = 0f
        var norm1 = 0f
        var norm2 = 0f
        for (i in v1.indices) {
            dot += v1[i] * v2[i]
            norm1 += v1[i] * v1[i]
            norm2 += v2[i] * v2[i]
        }
        val denom = sqrt(norm1) * sqrt(norm2)
        return if (denom == 0f) 0f else (dot / denom)
    }
}
