// NavControllerUtils.kt

package com.saerling.moody.ui.utils

import androidx.navigation.NavController
import com.saerling.moody.ui.screens.Screens

fun NavController.backToMain() {
    val mainRoutes = Screens.MainScreens.map { it.route }

    while (previousBackStackEntry != null &&
        currentBackStackEntry?.destination?.route !in mainRoutes
    ) {
        popBackStack()
    }
}
