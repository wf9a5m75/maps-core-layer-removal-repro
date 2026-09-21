package com.example.ommstall

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import io.openmobilemaps.mapscore.MapsCore
import io.openmobilemaps.mapscore.map.loader.DataLoader
import io.openmobilemaps.mapscore.map.view.MapView
import io.openmobilemaps.mapscore.map.view.MapViewState
import io.openmobilemaps.mapscore.shared.map.MapConfig
import io.openmobilemaps.mapscore.shared.map.LayerInterface
import io.openmobilemaps.mapscore.shared.map.MapInterface
import io.openmobilemaps.mapscore.shared.map.coordinates.Coord
import io.openmobilemaps.mapscore.shared.map.coordinates.CoordinateSystemFactory
import io.openmobilemaps.mapscore.shared.map.coordinates.CoordinateSystemIdentifiers
import io.openmobilemaps.mapscore.shared.map.layers.tiled.DefaultTiled2dMapLayerConfigs
import io.openmobilemaps.mapscore.shared.map.layers.tiled.raster.Tiled2dMapRasterLayerInterface
import io.openmobilemaps.mapscore.shared.map.loader.LoaderInterface
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Two raster layers over an in-app tile server; the camera moves on a timer.
 *
 * The slow layer stops requesting tiles after a while and never resumes,
 * while the fast layer over the same server keeps going. See README.md.
 */
class MainActivity : ComponentActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var server: SlowTileServer
    private lateinit var mapView: MapView

    private var moves = 0
    private var lastMoves = 0
    private var lastFast = 0
    private var lastSlow = 0
    private var lastOrphan = 0
    private var starvedWindows = 0
    private var quietWindows = 0
    private var reported = false
    private var slowLayer: LayerInterface? = null
    private var slowRaster: Tiled2dMapRasterLayerInterface? = null
    private var slowVersion = 0
    private lateinit var slowLoaders: ArrayList<LoaderInterface>

    /** Replace the overlay layer periodically, as an app does when its data changes. */
    private val swapLayers: Boolean get() = intent.getBooleanExtra("swap", true)

    /** Both layers through one DataLoader, as an app would normally have it. */
    private val shareLoader: Boolean get() = intent.getBooleanExtra("shared_loader", true)

    private val slowTileMs: Long get() = intent.getIntExtra("slow_ms", 800).toLong()

    /** Call pause() on the old layer before removing it, to see whether that stops its loads. */
    private val pauseBeforeRemove: Boolean get() = intent.getBooleanExtra("pause_before_remove", false)

    /** Call setTileLoadingPaused(true) on the old layer before removing it. */
    private val stopLoadingBeforeRemove: Boolean
        get() = intent.getBooleanExtra("stop_loading_before_remove", false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        MapsCore.initialize()

        server = SlowTileServer(slowDelayMs = slowTileMs).also { it.start() }
        Log.i(
            TAG,
            "config: slowTileMs=$slowTileMs swapLayers=$swapLayers shareLoader=$shareLoader " +
                "port=${server.port}",
        )

        mapView = MapView(this)
        setContentView(mapView)
        mapView.setupMap(
            MapConfig(CoordinateSystemFactory.getEpsg3857System()),
            resources.displayMetrics.densityDpi.toFloat(),
            false,
            false,
        )
        mapView.registerLifecycle(lifecycle)

        lifecycleScope.launch {
            mapView.mapViewState.first { it != MapViewState.UNINITIALIZED }
            val map = mapView.requireMapInterface()

            val fastLoaders = newLoaders()
            slowLoaders = if (shareLoader) fastLoaders else newLoaders()

            val fast =
                Tiled2dMapRasterLayerInterface.create(
                    DefaultTiled2dMapLayerConfigs.webMercator("fast", server.fastUrlTemplate()),
                    fastLoaders,
                )
            map.insertLayerAt(fast.asLayerInterface(), 0)

            replaceSlowLayer(map)
            startCameraLoop(map)
            if (swapLayers) startLayerSwapLoop(map)
            startWatchdog()
        }
    }

    private fun newLoaders(): ArrayList<LoaderInterface> =
        arrayListOf(DataLoader(this, cacheDir, CACHE_BYTES, "", USER_AGENT))

    /**
     * Swaps the overlay for a freshly created layer, which is what an app does
     * when the data behind its tiles changes: the tile URL carries a version,
     * so a new layer replaces the old one and the old tiles are dropped.
     */
    private fun replaceSlowLayer(map: MapInterface) {
        slowLayer?.let { old ->
            if (stopLoadingBeforeRemove) slowRaster?.setTileLoadingPaused(true)
            if (pauseBeforeRemove) old.pause()
            map.removeLayer(old)
        }
        slowVersion += 1
        val template = server.slowUrlTemplate() + "?v=$slowVersion"
        val layer =
            Tiled2dMapRasterLayerInterface.create(
                DefaultTiled2dMapLayerConfigs.webMercator("slow-$slowVersion", template),
                slowLoaders,
            )
        val asLayer = layer.asLayerInterface()
        map.insertLayerAt(asLayer, 1)
        slowLayer = asLayer
        slowRaster = layer
        server.setCurrentVersion(slowVersion)
        Log.i(TAG, "overlay layer replaced, version=$slowVersion (after $moves camera moves)")
    }

    private fun startLayerSwapLoop(map: MapInterface) {
        val swap =
            object : Runnable {
                override fun run() {
                    replaceSlowLayer(map)
                    handler.postDelayed(this, SWAP_INTERVAL_MS)
                }
            }
        handler.postDelayed(swap, SWAP_INTERVAL_MS)
    }

    /**
     * Walks the camera over ground it has not visited, so a layer that is
     * working has to keep asking for tiles. Revisiting positions would let a
     * healthy layer answer from its own tile cache, which looks the same from
     * the outside as the failure this is trying to catch.
     */
    private fun startCameraLoop(map: MapInterface) {
        val step =
            object : Runnable {
                override fun run() {
                    moves += 1
                    map.getCamera().moveToCenterPositionZoom(
                        Coord(
                            CoordinateSystemIdentifiers.EPSG3857(),
                            CENTER_X + moves * STEP_METERS,
                            CENTER_Y + moves * STEP_METERS / 8.0,
                            0.0,
                        ),
                        ZOOM_SCALE,
                        false,
                    )
                    handler.postDelayed(this, MOVE_INTERVAL_MS)
                }
            }
        handler.post(step)
    }

    /** Reports, every few seconds, which layer is still asking for tiles. */
    private fun startWatchdog() {
        val tick =
            object : Runnable {
                override fun run() {
                    val fast = server.fastCount()
                    val slow = server.slowCount()
                    val orphan = server.orphanCount()
                    val movedBy = moves - lastMoves
                    val fastBy = fast - lastFast
                    val slowBy = slow - lastSlow
                    val orphanBy = orphan - lastOrphan
                    Log.i(
                        TAG,
                        "WINDOW moves=+$movedBy fixedLayer=+$fastBy replacedLayer=+$slowBy " +
                            "ofWhichAlreadyRemoved=+$orphanBy | totals fixedLayer=$fast " +
                            "replacedLayer=$slow alreadyRemoved=$orphan",
                    )

                    // The camera is walking onto ground neither layer has seen,
                    // so a working layer has to keep asking for tiles. The one
                    // layer that is never touched is the one that goes quiet.
                    val starved = movedBy > 0 && fastBy * 4 < slowBy
                    if (starved) starvedWindows += 1 else starvedWindows = 0
                    if (starvedWindows >= QUIET_WINDOWS_TO_REPORT && !reported) {
                        reported = true
                        Log.e(
                            TAG,
                            "*** the layer that was never touched has been starved for " +
                                "${starvedWindows * WATCHDOG_INTERVAL_MS / 1000}s: it fetched $fastBy tiles " +
                                "while the replaced layer fetched $slowBy, $orphanBy of them for layers " +
                                "already removed from the map ***",
                        )
                    }
                    if (!starved && reported) {
                        Log.i(TAG, "the starved layer recovered after $moves camera moves")
                        reported = false
                    }

                    lastMoves = moves
                    lastFast = fast
                    lastSlow = slow
                    lastOrphan = orphan
                    handler.postDelayed(this, WATCHDOG_INTERVAL_MS)
                }
            }
        handler.postDelayed(tick, WATCHDOG_INTERVAL_MS)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private companion object {
        const val TAG = "OmmStall"

        /** How often the camera moves. */
        const val MOVE_INTERVAL_MS = 300L

        /** How often the overlay layer is replaced by a fresh one. */
        const val SWAP_INTERVAL_MS = 4000L

        const val WATCHDOG_INTERVAL_MS = 3000L
        const val QUIET_WINDOWS_TO_REPORT = 3

        /** Roughly half a screen at this scale, so each move needs new tiles. */
        const val STEP_METERS = 400.0

        /** Central Tokyo in EPSG:3857 metres. Any populated spot would do. */
        const val CENTER_X = 15_558_000.0
        const val CENTER_Y = 4_257_000.0

        /** Map scale (1:N), around what a phone shows at street level. */
        const val ZOOM_SCALE = 10_000.0

        const val CACHE_BYTES = 1L * 1024 * 1024
        const val USER_AGENT = "omm-stall-repro"
    }
}
