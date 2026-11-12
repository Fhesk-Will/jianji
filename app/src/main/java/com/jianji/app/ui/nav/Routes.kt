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

    /** 新建记录保存后回传给明细页的落点日期（epochDay），仅内存态、不参与路由 */
    const val SAVED_LOCATE_DAY = "savedLocateEpochDay"

    /** recordId 为 null = 新增，否则编辑 */
    fun record(recordId: String? = null): String =
        if (recordId == null) "record" else "record?$RECORD_ARG=$recordId"

    fun dayDetail(epochDay: Int): String = "day?$DAY_DETAIL_ARG=$epochDay"
}
