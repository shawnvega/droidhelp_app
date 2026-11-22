package com.bighelp.droidhelp.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import java.net.URLEncoder

private const val UPLOAD_CHUNK_SIZE = 16_384 // 16KB

data class UploadPayload(
    val url: String,
    val name: String,
    val fileName: String,
    val mimeType: String,
    val data: ByteArray,
)

interface HttpClient {
    suspend fun post(url: String, body: Map<String, String>): Response
    suspend fun upload(payload: UploadPayload, onProgress: (Float) -> Unit): Response
}

class OkHttpClientImpl(private val httpClient: OkHttpClient) : HttpClient {
    override suspend fun post(url: String, body: Map<String, String>): Response = withContext(Dispatchers.IO) {
        val requestBody = body.entries.joinToString("&") {
            "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}"
        }
        val mediaType = "application/x-www-form-urlencoded".toMediaTypeOrNull()
        val requestBodyObj = requestBody.toRequestBody(mediaType)
        val request = Request.Builder()
            .url(url)
            .post(requestBodyObj)
            .build()
        httpClient.newCall(request).execute()
    }

    override suspend fun upload(
        payload: UploadPayload,
        onProgress: (Float) -> Unit,
    ): Response = withContext(Dispatchers.IO) {
        val mediaType = payload.mimeType.toMediaTypeOrNull()
        val requestBody = object : RequestBody() {
            override fun contentType() = mediaType
            override fun contentLength() = payload.data.size.toLong()
            override fun writeTo(sink: BufferedSink) {
                var bytesWritten = 0L
                while (bytesWritten < payload.data.size) {
                    val remaining = payload.data.size - bytesWritten
                    val toWrite = minOf(UPLOAD_CHUNK_SIZE.toLong(), remaining).toInt()
                    sink.write(payload.data, bytesWritten.toInt(), toWrite)
                    bytesWritten += toWrite
                    onProgress(bytesWritten.toFloat() / payload.data.size)
                }
            }
        }

        val multipartBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(payload.name, payload.fileName, requestBody)
            .build()

        val request = Request.Builder()
            .url(payload.url)
            .post(multipartBody)
            .build()

        httpClient.newCall(request).execute()
    }
}
