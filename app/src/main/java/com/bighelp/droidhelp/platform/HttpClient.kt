package com.bighelp.droidhelp.platform

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface HttpClient {
    suspend fun post(url: String, body: Map<String, String>): Response
    suspend fun upload(
        url: String,
        name: String,
        fileName: String,
        mimeType: String,
        data: ByteArray,
        onProgress: (Float) -> Unit
    ): Response
}

class OkHttpClientImpl(private val httpClient: OkHttpClient) : HttpClient {
    override suspend fun post(url: String, body: Map<String, String>): Response = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val requestBody = body.entries.joinToString("&") { "${java.net.URLEncoder.encode(it.key, "UTF-8")}=${java.net.URLEncoder.encode(it.value, "UTF-8")}" }
        val mediaType = "application/x-www-form-urlencoded".toMediaTypeOrNull()
        val requestBodyObj = requestBody.toRequestBody(mediaType)
        val request = Request.Builder()
            .url(url)
            .post(requestBodyObj)
            .build()
        httpClient.newCall(request).execute()
    }

    override suspend fun upload(
        url: String,
        name: String,
        fileName: String,
        mimeType: String,
        data: ByteArray,
        onProgress: (Float) -> Unit
    ): Response = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val mediaType = mimeType.toMediaTypeOrNull()
        val requestBody = object : RequestBody() {
            override fun contentType() = mediaType
            override fun contentLength() = data.size.toLong()
            override fun writeTo(sink: okio.BufferedSink) {
                var bytesWritten = 0L
                val chunkSize = 16384 // 16KB
                while (bytesWritten < data.size) {
                    val toWrite = minOf(chunkSize.toLong(), (data.size - bytesWritten).toLong()).toInt()
                    sink.write(data, bytesWritten.toInt(), toWrite)
                    bytesWritten += toWrite
                    onProgress(bytesWritten.toFloat() / data.size)
                }
            }
        }

        val multipartBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(name, fileName, requestBody)
            .build()

        val request = Request.Builder()
            .url(url)
            .post(multipartBody)
            .build()

        httpClient.newCall(request).execute()
    }
}
