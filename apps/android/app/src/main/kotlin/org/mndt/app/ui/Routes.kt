package org.mndt.app.ui

object Routes {
    const val TIMELINE = "timeline"
    const val MAP = "map?focus={focus}"
    const val SETTINGS = "settings"
    const val POST = "post/{id}"
    fun map(focus: String? = null) = if (focus == null) "map" else "map?focus=$focus"
    fun post(id: String) = "post/$id"
}
