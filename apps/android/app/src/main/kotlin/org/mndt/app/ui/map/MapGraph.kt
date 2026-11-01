package org.mndt.app.ui.map

import androidx.compose.material3.Text
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import org.koin.dsl.module
import org.mndt.app.ui.Routes

val mapModule = module { }

fun NavGraphBuilder.mapGraph(nav: NavController) {
    composable(Routes.MAP, arguments = listOf(navArgument("focus") { type = NavType.StringType; nullable = true })) { Text("状況マップ") }
}
