package com.example.navigation

import android.net.Uri
import com.example.domain.model.SettingsCategory

object AgtRoutes {
    const val SETUP = "setup"
    const val GITHUB_CONNECT = "github_connect"
    const val HOME = "home"
    const val EXTENSIONS = "extensions"
    const val SETTINGS = "settings"

    const val ARG_PROJECT_ID = "projectId"
    const val WORKSPACE_PATTERN = "workspace/{$ARG_PROJECT_ID}"
    fun workspace(projectId: String): String = "workspace/${Uri.encode(projectId)}"

    const val ARG_SETTINGS_CATEGORY = "category"
    const val SETTINGS_DETAIL_PATTERN = "settings/{$ARG_SETTINGS_CATEGORY}"
    fun settingsDetail(category: SettingsCategory): String = "settings/${category.routeKey}"
}
