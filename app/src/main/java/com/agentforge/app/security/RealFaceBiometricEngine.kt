package com.agentforge.app.security

import android.graphics.PointF
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceLandmark
import kotlin.math.hypot
import kotlin.math.sqrt

object RealFaceBiometricEngine {

    fun extractBiometricHash(face: Face): String? {
        val leftEye = face.getLandmark(FaceLandmark.LEFT_EYE)?.position
        val rightEye = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position
        val nose = face.getLandmark(FaceLandmark.NOSE_BASE)?.position
        val mouthLeft = face.getLandmark(FaceLandmark.MOUTH_LEFT)?.position
        val mouthRight = face.getLandmark(FaceLandmark.MOUTH_RIGHT)?.position

        if (leftEye == null || rightEye == null || nose == null) {
            return null
        }

        // Inter-ocular distance normalization scale
        val eyeDistance = distance(leftEye, rightEye)
        if (eyeDistance < 10f) return null

        val dNoseToLeftEye = distance(nose, leftEye) / eyeDistance
        val dNoseToRightEye = distance(nose, rightEye) / eyeDistance
        val dMouthToNose = if (mouthLeft != null && mouthRight != null) {
            val mouthCenter = PointF((mouthLeft.x + mouthRight.x) / 2f, (mouthLeft.y + mouthRight.y) / 2f)
            distance(mouthCenter, nose) / eyeDistance
        } else {
            0.65f
        }

        val yaw = face.headEulerAngleY
        val pitch = face.headEulerAngleX

        return String.format(
            "%.3f,%.3f,%.3f,%.1f,%.1f",
            dNoseToLeftEye, dNoseToRightEye, dMouthToNose, yaw, pitch
        )
    }

    fun verifyFaces(registeredHash: String, currentHash: String): Boolean {
        return try {
            val regParts = registeredHash.split(",").map { it.toFloat() }
            val curParts = currentHash.split(",").map { it.toFloat() }

            if (regParts.size < 5 || curParts.size < 5) return false

            val d1 = regParts[0] - curParts[0]
            val d2 = regParts[1] - curParts[1]
            val d3 = regParts[2] - curParts[2]

            // Euclidean distance of normalized geometric facial points
            val geometricDiff = sqrt(d1 * d1 + d2 * d2 + d3 * d3)

            // Tolerance threshold for matching true owner
            geometricDiff < 0.22f
        } catch (_: Exception) {
            false
        }
    }

    private fun distance(p1: PointF, p2: PointF): Float {
        return hypot(p1.x - p2.x, p1.y - p2.y)
    }
}
