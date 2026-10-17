package com.example.api

import com.squareup.moshi.JsonClass
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import java.util.concurrent.TimeUnit

@JsonClass(generateAdapter = true)
data class ChatTurn(val role: String, val content: String)

@JsonClass(generateAdapter = true)
data class ChatRequest(val messages: List<ChatTurn>)

@JsonClass(generateAdapter = true)
data class ChatResponse(val reply: String)

interface GlassAiService {
    @POST("api/chat")
    suspend fun chat(@Body request: ChatRequest): ChatResponse
}

object RetrofitClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    val glassAiService: GlassAiService by lazy {
        Retrofit.Builder()
            .baseUrl("https://app-glass.vercel.app/")
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create())
            .build()
            .create(GlassAiService::class.java)
    }
}
