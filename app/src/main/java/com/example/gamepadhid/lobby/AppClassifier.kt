package com.example.gamepadhid.lobby

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import com.example.gamepadhid.R

enum class LobbyCategory(val titleRes: Int, val iconRes: Int) {
    GAMES(R.string.lobby_cat_games, R.drawable.ic_cat_games),
    ENTERTAINMENT(R.string.lobby_cat_entertainment, R.drawable.ic_cat_video),
    APPS(R.string.lobby_cat_apps, R.drawable.ic_cat_apps),
    SETTINGS(R.string.lobby_cat_settings, R.drawable.ic_cat_settings)
}

enum class LobbyAction { LAUNCH_APP, EXIT_CONSOLE }

data class LobbyItem(
    val label: String,
    val subtitle: String,
    val category: LobbyCategory,
    val action: LobbyAction,
    val packageName: String? = null
)

/** Clasifica las apps instaladas con PackageManager (sin QUERY_ALL_PACKAGES: solo las que tienen icono de lanzador). */
object AppClassifier {

    /** Fragmentos de nombre de paquete de apps de vídeo/música conocidas. */
    private val ENTERTAINMENT_HINTS = listOf(
        "youtube", "netflix", "disneyplus", "hbo", "primevideo", "amazon.avod", "twitch", "spotify", "plex",
        "kodi", "videolan", "crunchyroll", "hulu", "pluto", "paramount", "apple.atve", "deezer", "tidal",
        "soundcloud", "dazn", "mxtech"
    )

    @Suppress("DEPRECATION")
    fun classify(info: ApplicationInfo): LobbyCategory {
        if (info.category == ApplicationInfo.CATEGORY_GAME || (info.flags and ApplicationInfo.FLAG_IS_GAME) != 0) {
            return LobbyCategory.GAMES
        }
        val pkg = info.packageName.lowercase()
        if (ENTERTAINMENT_HINTS.any { pkg.contains(it) }) return LobbyCategory.ENTERTAINMENT
        return when (info.category) {
            ApplicationInfo.CATEGORY_VIDEO, ApplicationInfo.CATEGORY_AUDIO -> LobbyCategory.ENTERTAINMENT
            else -> LobbyCategory.APPS
        }
    }

    /** Llamar fuera del hilo principal. Devuelve las apps clasificadas más los elementos de Ajustes. */
    fun buildItems(context: Context): List<LobbyItem> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val infos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            pm.queryIntentActivities(intent, 0)
        }
        val apps = infos
            .filter { it.activityInfo.packageName != context.packageName }
            .distinctBy { it.activityInfo.packageName }
            .map {
                val category = classify(it.activityInfo.applicationInfo)
                LobbyItem(
                    label = it.loadLabel(pm).toString(),
                    subtitle = context.getString(category.titleRes),
                    category = category,
                    action = LobbyAction.LAUNCH_APP,
                    packageName = it.activityInfo.packageName
                )
            }
            .sortedBy { it.label.lowercase() }

        val settings = listOf(
            LobbyItem(
                context.getString(R.string.lobby_item_exit),
                context.getString(R.string.lobby_item_exit_sub),
                LobbyCategory.SETTINGS, LobbyAction.EXIT_CONSOLE
            )
        )
        return apps + settings
    }
}
