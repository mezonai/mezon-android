package com.mezon.mobile.util

import com.mezon.mobile.BuildConfig
import com.mezon.mobile.home.chat.MessageEntity
import org.json.JSONObject
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.roundToInt
import kotlin.math.tan

data class LocationMessageData(
    val latitude: Double,
    val longitude: Double,
    val mapsUrl: String
)

private val LOCATION_COORDINATE_REGEX = Regex("q=(-?\\d+\\.?\\d*),(-?\\d+\\.?\\d*)")
private const val STATIC_MAP_ZOOM = 16
private const val STATIC_MAP_MAX_EDGE_PX = 640
private const val MAP_SPAN_METERS = 500.0
private const val METERS_PER_PIXEL_ZOOM0 = 156543.03392
private const val TILE_SUBDOMAINS = "abc"
const val MAP_TILE_PX = 256

class MapTile(val url: String, val left: Float, val top: Float)

class MapTilePlan(val key: String, val tiles: List<MapTile>, val styledDark: Boolean)

class StaticMapImage(val url: String, val styledDark: Boolean)

fun isLocationMessage(code: Int, content: String): Boolean =
    code == MessageEntity.CODE_LOCATION && parseLocationMessageData(content) != null

fun parseLocationMessageData(content: String): LocationMessageData? {
    if (content.isBlank() || !content.contains("q=")) return null
    val text = try {
        JSONObject(content).optString("t", "")
    } catch (_: Exception) {
        return null
    }
    if (text.isEmpty()) return null
    val match = LOCATION_COORDINATE_REGEX.find(text) ?: return null
    val latitude = match.groupValues[1].toDoubleOrNull() ?: return null
    val longitude = match.groupValues[2].toDoubleOrNull() ?: return null
    if (latitude < -90.0 || latitude > 90.0 || longitude < -180.0 || longitude > 180.0) return null
    return LocationMessageData(latitude, longitude, text)
}

fun staticMapImage(
    latitude: Double,
    longitude: Double,
    widthPx: Int,
    heightPx: Int,
    dark: Boolean
): StaticMapImage? {
    val darkTemplate = BuildConfig.MEZON_STATIC_MAP_URL_TEMPLATE_DARK.trim()
    val useDark = dark && darkTemplate.isNotEmpty()
    val template = if (useDark) darkTemplate else BuildConfig.MEZON_STATIC_MAP_URL_TEMPLATE.trim()
    if (template.isEmpty() || widthPx <= 0 || heightPx <= 0) return null
    val scale = if (widthPx > STATIC_MAP_MAX_EDGE_PX || heightPx > STATIC_MAP_MAX_EDGE_PX) 2 else 1
    val url = template
        .replace("{lat}", String.format(Locale.US, "%.6f", latitude))
        .replace("{lng}", String.format(Locale.US, "%.6f", longitude))
        .replace("{zoom}", STATIC_MAP_ZOOM.toString())
        .replace("{width}", (widthPx / scale).toString())
        .replace("{height}", (heightPx / scale).toString())
        .replace("{scale}", scale.toString())
        .replace("{theme}", if (dark) "dark" else "light")
    return StaticMapImage(url, useDark)
}

fun mapTilePlan(latitude: Double, longitude: Double, widthPx: Int, heightPx: Int, dark: Boolean): MapTilePlan? {
    val darkTemplate = BuildConfig.MEZON_MAP_TILE_URL_TEMPLATE_DARK.trim()
    val useDark = dark && darkTemplate.isNotEmpty()
    val template = if (useDark) darkTemplate else BuildConfig.MEZON_MAP_TILE_URL_TEMPLATE.trim()
    if (template.isEmpty() || widthPx <= 0 || heightPx <= 0) return null
    val zoom = tileZoomForSpan(latitude, widthPx)
    val n = 1 shl zoom
    val latRad = Math.toRadians(latitude)
    val xTile = (longitude + 180.0) / 360.0 * n
    val yTile = (1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * n
    val left = xTile * MAP_TILE_PX - widthPx / 2.0
    val top = yTile * MAP_TILE_PX - heightPx / 2.0
    val firstCol = floor(left / MAP_TILE_PX).toInt()
    val lastCol = floor((left + widthPx - 1) / MAP_TILE_PX).toInt()
    val firstRow = floor(top / MAP_TILE_PX).toInt()
    val lastRow = floor((top + heightPx - 1) / MAP_TILE_PX).toInt()
    val tiles = ArrayList<MapTile>()
    for (row in firstRow..lastRow) {
        if (row < 0 || row >= n) continue
        for (col in firstCol..lastCol) {
            val wrappedCol = ((col % n) + n) % n
            val subdomain = TILE_SUBDOMAINS[((col + row) % TILE_SUBDOMAINS.length + TILE_SUBDOMAINS.length) % TILE_SUBDOMAINS.length]
            val url = template
                .replace("{s}", subdomain.toString())
                .replace("{r}", "")
                .replace("{z}", zoom.toString())
                .replace("{x}", wrappedCol.toString())
                .replace("{y}", row.toString())
            tiles += MapTile(url, (col * MAP_TILE_PX - left).toFloat(), (row * MAP_TILE_PX - top).toFloat())
        }
    }
    val key = String.format(Locale.US, "%d/%.6f/%.6f/%dx%d/%s", zoom, latitude, longitude, widthPx, heightPx, template)
    return MapTilePlan(key, tiles, useDark)
}

private fun tileZoomForSpan(latitude: Double, widthPx: Int): Int {
    val metersPerPixelAtZoom0 = METERS_PER_PIXEL_ZOOM0 * cos(Math.toRadians(latitude)).coerceAtLeast(0.01)
    val zoom = log2(metersPerPixelAtZoom0 * widthPx / MAP_SPAN_METERS)
    return zoom.roundToInt().coerceIn(3, 19)
}
