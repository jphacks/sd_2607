package org.mndt.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import kotlinx.serialization.Serializable

@Serializable data object TimelineKey : NavKey
/** [focus]: post id to centre and open. */
@Serializable data class MapKey(val focus: String? = null) : NavKey
@Serializable data object SettingsKey : NavKey
@Serializable data class PostKey(val id: String) : NavKey

/** Bottom bar order, which also decides the slide direction between tabs. */
enum class Tab { TIMELINE, MAP, SETTINGS }

/** One back stack per tab. The map is the start tab, so other tabs sit on top of it and back from their root returns to the map. */
class Navigator(private val current: MutableState<Tab>, private val stacks: Map<Tab, NavBackStack<NavKey>>) {
    val tab get() = current.value
    /** Toward the end of the bar or deeper: slide in from the end; otherwise from the start. */
    var forward by mutableStateOf(true)
        private set
    val backStack: List<NavKey> get() = stacks.getValue(Tab.MAP) + if (tab == Tab.MAP) emptyList() else stacks.getValue(tab)
    val atTabRoot get() = stacks.getValue(tab).size == 1

    fun select(target: Tab) {
        if (target == tab) return
        forward = target > tab
        current.value = target
    }
    fun push(key: NavKey) {
        forward = true
        stacks.getValue(tab).add(key)
    }
    fun pop() {
        forward = false
        val stack = stacks.getValue(tab)
        if (stack.size > 1) stack.removeAt(stack.lastIndex) else current.value = Tab.MAP
    }
    fun showOnMap(id: String) {
        stacks.getValue(Tab.MAP).apply { clear(); add(MapKey(id)) }
        forward = Tab.MAP > tab
        current.value = Tab.MAP
    }
}

@Composable
fun rememberNavigator(): Navigator {
    val current = rememberSaveable { mutableStateOf(Tab.MAP) }
    val stacks = mapOf(Tab.TIMELINE to rememberNavBackStack(TimelineKey), Tab.MAP to rememberNavBackStack(MapKey()),
        Tab.SETTINGS to rememberNavBackStack(SettingsKey))
    return remember { Navigator(current, stacks) }
}
