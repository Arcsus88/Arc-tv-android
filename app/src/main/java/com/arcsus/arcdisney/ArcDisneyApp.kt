package com.arcsus.arcdisney

import android.app.Application
import android.content.Context
import com.arcsus.arcdisney.data.CatalogueStore
import com.arcsus.arcdisney.data.Settings
import com.arcsus.arcdisney.torrent.TorrentEngine
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

class ArcDisneyApp : Application() {
    val graph: AppGraph by lazy { AppGraph(this) }
}

/** The app's single instances. */
class AppGraph(context: Context) {
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .build()

    val store = CatalogueStore(File(context.filesDir, "catalogue.json")) { context.assets.open("disney.json") }

    val settings = Settings(context)

    /** Films download to the cache while they play and are deleted after. */
    val engine: TorrentEngine by lazy { TorrentEngine(File(context.cacheDir, "torrents")) }
}
