package com.school.faceverify.net

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.ImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object FacePhotoLoader {
    private val jobs = mutableMapOf<ImageView, Job>()

    fun loadInto(
        scope: CoroutineScope,
        imageView: ImageView,
        client: FaceApiClient,
        studentId: String,
        placeholderRes: Int,
        onLoaded: ((Boolean) -> Unit)? = null,
    ) {
        jobs.remove(imageView)?.cancel()
        imageView.setImageResource(placeholderRes)
        imageView.tag = studentId
        val job = scope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching { client.fetchFacePhoto(studentId) }.getOrNull()
            }
            if (imageView.tag != studentId) return@launch
            if (bytes == null) {
                onLoaded?.invoke(false)
                return@launch
            }
            val bitmap = withContext(Dispatchers.Default) {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
            if (imageView.tag != studentId || bitmap == null) {
                onLoaded?.invoke(false)
                return@launch
            }
            imageView.setImageBitmap(bitmap)
            onLoaded?.invoke(true)
        }
        jobs[imageView] = job
    }

    fun cancel(imageView: ImageView) {
        jobs.remove(imageView)?.cancel()
        imageView.tag = null
    }
}
