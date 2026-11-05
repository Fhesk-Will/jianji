package com.jianji.app.ui.nav

object Routes {
    const val HOME = "home"
    const val STATS = "stats"
    const val SETTINGS = "settings"
    const val CATEGORIES = "categories"
    const val RECORD_ARG = "recordId"
    const val RECORD = "record?$RECORD_ARG={$RECORD_ARG}"

    /** recordId 为 null = 新增，否则编辑 */
    fun record(recordId: String? = null): String =
        if (recordId == null) "record" else "record?$RECORD_ARG=$recordId"
}
