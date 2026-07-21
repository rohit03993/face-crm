package com.school.faceverify.face

import android.graphics.Bitmap
import android.graphics.PointF
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import kotlinx.coroutines.tasks.await
import kotlin.math.abs

data class FacePoseReading(
    val yaw: Float,
    val pitch: Float,
    val hasLandmarks: Boolean,
)

data class FaceEmbedSample(
    val embedding: FloatArray,
    val yaw: Float,
    val pitch: Float,
    val aligned: Bitmap,
)

/** Guided enrollment: one clear frontal live capture (matches punch pose). */
enum class EnrollPose(
    val id: String,
    val hintRes: Int,
) {
    FRONT("front", com.school.faceverify.R.string.enroll_pose_front),
    ;

    fun matches(yaw: Float, pitch: Float): Boolean = when (this) {
        FRONT -> abs(yaw) <= 12f && abs(pitch) <= 12f
    }

    companion object {
        val SEQUENCE = listOf(FRONT)
    }
}

class FacePipeline(private val embedder: ArcFaceEmbedder) {
    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setMinFaceSize(0.15f)
            .build(),
    )

    suspend fun detectPose(bitmap: Bitmap): FacePoseReading? {
        val face = largestFace(bitmap) ?: return null
        return FacePoseReading(
            yaw = face.headEulerAngleY,
            pitch = face.headEulerAngleX,
            hasLandmarks = landmarksOrNull(face) != null,
        )
    }

    suspend fun detectPresence(bitmap: Bitmap): PresenceReading? {
        val face = largestFace(bitmap) ?: return null
        return PresenceReading(
            yaw = face.headEulerAngleY,
            pitch = face.headEulerAngleX,
            leftEyeOpen = face.leftEyeOpenProbability,
            rightEyeOpen = face.rightEyeOpenProbability,
            faceBounds = face.boundingBox,
            hasLandmarks = landmarksOrNull(face) != null,
        )
    }

    suspend fun embedFromBitmap(bitmap: Bitmap): Pair<FloatArray, Bitmap>? {
        val sample = embedWithPose(bitmap) ?: return null
        return sample.embedding to sample.aligned
    }

    suspend fun embedWithPose(bitmap: Bitmap): FaceEmbedSample? {
        val face = largestFace(bitmap) ?: return null
        val landmarks = landmarksOrNull(face) ?: return null
        val aligned = FaceAligner.align(bitmap, landmarks)
        val emb = embedder.embed(aligned)
        return FaceEmbedSample(
            embedding = emb,
            yaw = face.headEulerAngleY,
            pitch = face.headEulerAngleX,
            aligned = aligned,
        )
    }

    private suspend fun largestFace(bitmap: Bitmap): Face? {
        val image = InputImage.fromBitmap(bitmap, 0)
        val faces = detector.process(image).await()
        if (faces.isEmpty()) return null
        return faces.maxBy { it.boundingBox.width() * it.boundingBox.height() }
    }

    private fun landmarksOrNull(face: Face): List<PointF>? {
        val leftEye = face.getLandmark(FaceLandmark.LEFT_EYE)?.position
        val rightEye = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position
        val nose = face.getLandmark(FaceLandmark.NOSE_BASE)?.position
        val mouthLeft = face.getLandmark(FaceLandmark.MOUTH_LEFT)?.position
        val mouthRight = face.getLandmark(FaceLandmark.MOUTH_RIGHT)?.position
        if (leftEye == null || rightEye == null || nose == null || mouthLeft == null || mouthRight == null) {
            return null
        }
        return listOf(
            PointF(leftEye.x, leftEye.y),
            PointF(rightEye.x, rightEye.y),
            PointF(nose.x, nose.y),
            PointF(mouthLeft.x, mouthLeft.y),
            PointF(mouthRight.x, mouthRight.y),
        )
    }

    fun close() {
        detector.close()
    }
}
