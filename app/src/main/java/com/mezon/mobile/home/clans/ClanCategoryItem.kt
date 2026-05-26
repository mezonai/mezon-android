package com.mezon.mobile.home.clans

import com.mezon.mezon.api.CategoryDesc

data class ClanCategoryItem(
    val categoryId: Long,
    val categoryName: String,
    val categoryOrder: Int = 0,
    val clanId: Long = 0L,
)

fun CategoryDesc.toClanCategoryItem(): ClanCategoryItem = ClanCategoryItem(
    categoryId = categoryId,
    categoryName = categoryName,
    categoryOrder = categoryOrder,
    clanId = clanId,
)
