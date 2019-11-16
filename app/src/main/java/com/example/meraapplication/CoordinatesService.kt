package com.example.meraapplication

import com.google.gson.annotations.SerializedName
import retrofit2.Response
import retrofit2.http.GET

interface CoordinatesService {
    @GET("coordinates")
    suspend fun getCoordinates(): Response<CoordinatesPoint>
}

data class CoordinatesPoint(
    @SerializedName("X") val x:Int,
    @SerializedName("y") val y:Int
)