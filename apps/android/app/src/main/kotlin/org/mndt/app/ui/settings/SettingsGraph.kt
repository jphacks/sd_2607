package org.mndt.app.ui.settings

import androidx.compose.material3.Text
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import org.koin.dsl.module
import org.mndt.app.ui.Routes

val settingsModule = module { }

fun NavGraphBuilder.settingsGraph(nav: NavController) {
    composable(Routes.SETTINGS) { Text("設定") }
}
