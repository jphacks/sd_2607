package org.mndt.app.ui.timeline

import androidx.compose.material3.Text
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import org.koin.dsl.module
import org.mndt.app.ui.Routes

val timelineModule = module { }

fun NavGraphBuilder.timelineGraph(nav: NavController) {
    composable(Routes.TIMELINE) { Text("タイムライン") }
    composable(Routes.POST) { Text("投稿") }
}
