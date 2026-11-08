package com.jianji.app.ui.nav

object Routes {
    const val HOME = "home"
    const val STATS = "stats"
    const val SETTINGS = "settings"
    const val CATEGORIES = "categories"
    const val RECORD_ARG = "recordId"
    const val RECORD = "record?$RECORD_ARG={$RECORD_ARG}"
    const val DAY_DETAIL_ARG = "epochDay"
    const val DAY_DETAIL = "day?$DAY_DETAIL_ARG={$DAY_DETAIL_ARG}"

    /** recordId 为 null = 新增，否则编辑 */
    fun record(recordId: String? = null): String =
        if (recordId == null) "record" else "record?$RECORD_ARG=$recordId"

    fun dayDetail(epochDay: Int): String = "day?$DAY_DETAIL_ARG=$epochDay"
}
