package com.arcsus.arcdisney

import android.app.Application
import android.content.Context
import com.arcsus.arcdisney.data.CatalogueStore
import com.arcsus.arcdisney.data.Settings
import com.arcsus.arcdisney.debrid.AllDebrid
import com.arcsus.arcdisney.torrent.TorrentEngine
import com.arcsus.arcdisney.update.AutoUpdater
import com.arcsus.arcdisney.update.ReleaseClient
import com.arcsus.arcdisney.wifi.WifiController
import okhttp3.OkHttpClient
import org.videolan.libvlc.LibVLC
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class ArcDisneyApp : Application() {
    val graph: AppGraph by lazy { AppGraph(this) }

    override fun onCreate() {
        super.onCreate()
        graph.updates.start()
    }
}

/** The app's single instances. */
class AppGraph(context: Context) {
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .build()

    val store = CatalogueStore(File(context.filesDir, "catalogue.json")) { context.assets.open("disney.json") }

    val settings = Settings(context)

    val wifi: WifiController by lazy { WifiController(context) }

    /** Faster streaming for devices signed in to their own AllDebrid account. */
    val allDebrid = AllDebrid(http)

    /** Hourly checks for a newer GitHub release; builds without a token don't check. */
    val updates = AutoUpdater(
        context,
        releases = BuildConfig.UPDATE_TOKEN.takeIf { it.isNotBlank() }?.let { ReleaseClient(http, it, BuildConfig.UPDATE_REPO) },
        currentVersion = BuildConfig.VERSION_NAME,
    )

    /** Films download to the cache while they play and are deleted after; the DHT's nodes are kept. */
    val engine: TorrentEngine by lazy {
        TorrentEngine(File(context.cacheDir, "torrents"), TorrentEngine.Config(stateFile = File(context.filesDir, "torrent-session")))
    }

    /** The player's VLC, shared by every film: loading it is slow, so it happens once. */
    val vlc: LibVLC by lazy {
        LibVLC(context, arrayListOf("--network-caching=4000", "--http-reconnect", "--audio-time-stretch"))
    }

    /**
     * The app is on screen: start libtorrent (its DHT takes a while to find
     * peers) and load VLC now, rather than when the first film is picked.
     */
    fun warmUp() {
        engine.setForeground(true)
        thread(name = "vlc-warm-up") { runCatching { vlc } }
    }

    /** Off screen: libtorrent may stop once no film is open. */
    fun coolDown() = engine.setForeground(false)
}
