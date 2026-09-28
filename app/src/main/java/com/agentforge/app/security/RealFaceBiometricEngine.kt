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
        val noseBottom = face.getContour(FaceContour.NOSE_BOTTOM)?.points
        val upperLip = face.getContour(FaceContour.UPPER_LIP_TOP)?.points

        if (faceOval.isNullOrEmpty() || leftEye.isNullOrEmpty() || rightEye.isNullOrEmpty() ||
            noseBottom.isNullOrEmpty() || upperLip.isNullOrEmpty()) {
            return null
        }

        val leftEyeCenter = computeCenter(leftEye)
        val rightEyeCenter = computeCenter(rightEye)
        val eyeDistance = distance(leftEyeCenter, rightEyeCenter)
        if (eyeDistance < 15f) return null

        val noseTip = noseBottom[noseBottom.size / 2]
        val chin = faceOval[faceOval.size / 2]
        val forehead = faceOval[0]
        val mouthCenter = computeCenter(upperLip)

        // Scale & Distance Invariant Proportions
        val rEyeSpanToFaceWidth = eyeDistance / distance(faceOval[8], faceOval[28]).coerceAtLeast(1f)
        val rFaceHeight = distance(forehead, chin) / eyeDistance
        val rNoseToChin = distance(noseTip, chin) / eyeDistance
        val rMouthToChin = distance(mouthCenter, chin) / eyeDistance
        val rNoseToEyeLeft = distance(noseTip, leftEyeCenter) / eyeDistance
        val rNoseToEyeRight = distance(noseTip, rightEyeCenter) / eyeDistance
        val rNoseToMouth = distance(noseTip, mouthCenter) / eyeDistance
        val rForeheadToEye = distance(forehead, leftEyeCenter) / eyeDistance

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
            yaw,
            pitch
        ).joinToString(",") { String.format(java.util.Locale.US, "%.4f", it) }
    }

    fun verifyFaces(registeredHash: String, currentHash: String): Boolean {
        return try {
            val reg = registeredHash.split(",").map { it.toFloat() }
            val cur = currentHash.split(",").map { it.toFloat() }

            if (reg.size < 10 || cur.size < 10) return false

            // Pose allowance
            val yawDiff = abs(reg[8] - cur[8])
            val pitchDiff = abs(reg[9] - cur[9])
            if (yawDiff > 24f || pitchDiff > 20f) {
                return false
            }

            var sumSquared = 0.0
            for (i in 0 until 8) {
                val diff = reg[i] - cur[i]
                sumSquared += (diff * diff)
            }
            val euclideanDistance = sqrt(sumSquared)

            euclideanDistance < 0.145f
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
