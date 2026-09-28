package com.agentforge.app.security

import android.graphics.PointF
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceContour
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

object RealFaceBiometricEngine {

    fun extractBiometricHash(face: Face): String? {
        val faceOval = face.getContour(FaceContour.FACE)?.points
        val leftEye = face.getContour(FaceContour.LEFT_EYE)?.points
        val rightEye = face.getContour(FaceContour.RIGHT_EYE)?.points
        val noseBridge = face.getContour(FaceContour.NOSE_BRIDGE)?.points
        val noseBottom = face.getContour(FaceContour.NOSE_BOTTOM)?.points
        val upperLip = face.getContour(FaceContour.UPPER_LIP_TOP)?.points
        val lowerLip = face.getContour(FaceContour.LOWER_LIP_BOTTOM)?.points

        if (faceOval.isNullOrEmpty() || leftEye.isNullOrEmpty() || rightEye.isNullOrEmpty() ||
            noseBridge.isNullOrEmpty() || noseBottom.isNullOrEmpty() || upperLip.isNullOrEmpty()) {
            return null
        }

        // Center calculation
        val leftEyeCenter = computeCenter(leftEye)
        val rightEyeCenter = computeCenter(rightEye)
        val eyeDistance = distance(leftEyeCenter, rightEyeCenter)
        if (eyeDistance < 15f) return null

        val noseTip = noseBottom[noseBottom.size / 2]
        val chin = faceOval[faceOval.size / 2]
        val forehead = faceOval[0]
        val mouthCenter = computeCenter(upperLip)

        // 10 Key Normalized Geometric Ratios
        val rEyeSpanToFaceWidth = eyeDistance / distance(faceOval[8], faceOval[28]).coerceAtLeast(1f)
        val rFaceHeight = distance(forehead, chin) / eyeDistance
        val rNoseToChin = distance(noseTip, chin) / eyeDistance
        val rMouthToChin = distance(mouthCenter, chin) / eyeDistance
        val rNoseToEyeLeft = distance(noseTip, leftEyeCenter) / eyeDistance
        val rNoseToEyeRight = distance(noseTip, rightEyeCenter) / eyeDistance
        val rNoseToMouth = distance(noseTip, mouthCenter) / eyeDistance
        val rForeheadToEye = distance(forehead, leftEyeCenter) / eyeDistance
        val rMouthWidth = (if (!lowerLip.isNullOrEmpty()) distance(upperLip.first(), upperLip.last()) else eyeDistance * 0.7f) / eyeDistance
        val rJawWidth = distance(faceOval[12], faceOval[24]) / eyeDistance

        val yaw = face.headEulerAngleY
        val pitch = face.headEulerAngleX

        return listOf(
            rEyeSpanToFaceWidth,
            rFaceHeight,
            rNoseToChin,
            rMouthToChin,
            rNoseToEyeLeft,
            rNoseToEyeRight,
            rNoseToMouth,
            rForeheadToEye,
            rMouthWidth,
            rJawWidth,
            yaw,
            pitch
        ).joinToString(",") { String.format(java.util.Locale.US, "%.4f", it) }
    }

    fun verifyFaces(registeredHash: String, currentHash: String): Boolean {
        return try {
            val reg = registeredHash.split(",").map { it.toFloat() }
            val cur = currentHash.split(",").map { it.toFloat() }

            if (reg.size < 12 || cur.size < 12) return false

            // Pose Angle constraint (reject if head is tilted differently)
            val yawDiff = abs(reg[10] - cur[10])
            val pitchDiff = abs(reg[11] - cur[11])
            if (yawDiff > 14f || pitchDiff > 14f) {
                return false
            }

            var sumSquared = 0.0
            // Compare 10 normalized geometric traits
            for (i in 0 until 10) {
                val diff = reg[i] - cur[i]
                sumSquared += (diff * diff)
            }
            val euclideanDistance = sqrt(sumSquared)

            // Strict biometric threshold: allows minor natural expression variation but rejects different face structures
            euclideanDistance < 0.078f
        } catch (_: Exception) {
            false
        }
    }

    private fun computeCenter(points: List<PointF>): PointF {
        var x = 0f
        var y = 0f
        for (p in points) {
            x += p.x
            y += p.y
        }
        return PointF(x / points.size, y / points.size)
    }

    private fun distance(p1: PointF, p2: PointF): Float {
        return hypot(p1.x - p2.x, p1.y - p2.y)
    }
}
