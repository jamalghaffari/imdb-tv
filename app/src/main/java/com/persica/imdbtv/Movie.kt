package com.persica.imdbtv

import java.io.Serializable

data class Movie(
    val id: String,
    val rank: Int = 0,
    val titleFa: String,
    val titleEn: String,
    val year: Int,
    val rating: Double,
    val genre: String,
    val director: String,
    val plot: String,
    val poster: String,
    val streamUrl: String = ""
) : Serializable
