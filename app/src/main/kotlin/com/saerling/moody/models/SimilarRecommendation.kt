// SimilarRecommendation.kt

package com.saerling.moody.models

import com.music.innertube.models.YTItem
import com.saerling.moody.db.entities.LocalItem

data class SimilarRecommendation(
    val title: LocalItem,
    val items: List<YTItem>,
)
