package com.school.faceverify.face

import android.graphics.Bitmap
import android.graphics.PointF
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.tasks.await

class FacePipeline(private val embedder: ArcFaceEmbedder) {
    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setMinFaceSize(0.15f)
            .build()
    )

    suspend fun embedFromBitmap(bitmap: Bitmap): Pair<FloatArray, Bitmap>? {
        val image = InputImage.fromBitmap(bitmap, 0)
        val faces = detector.process(image).await()
        if (faces.isEmpty()) return null
        val face = faces.maxBy { it.boundingBox.width() * it.boundingBox.height() }
        val leftEye = face.getLandmark(com.google.mlkit.vision.face.FaceLandmark.LEFT_EYE)?.position
        val rightEye = face.getLandmark(com.google.mlkit.vision.face.FaceLandmark.RIGHT_EYE)?.position
        val nose = face.getLandmark(com.google.mlkit.vision.face.FaceLandmark.NOSE_BASE)?.position
        val mouthLeft = face.getLandmark(com.google.mlkit.vision.face.FaceLandmark.MOUTH_LEFT)?.position
        val mouthRight = face.getLandmark(com.google.mlkit.vision.face.FaceLandmark.MOUTH_RIGHT)?.position
        if (leftEye == null || rightEye == null || nose == null || mouthLeft == null || mouthRight == null) {
            return null
        }
        val landmarks = listOf(
            PointF(leftEye.x, leftEye.y),
            PointF(rightEye.x, rightEye.y),
            PointF(nose.x, nose.y),
            PointF(mouthLeft.x, mouthLeft.y),
            PointF(mouthRight.x, mouthRight.y),
        )
        val aligned = FaceAligner.align(bitmap, landmarks)
        val emb = embedder.embed(aligned)
        return emb to aligned
    }

    fun close() {
        detector.close()
    }
}
