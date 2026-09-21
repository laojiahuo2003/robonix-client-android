package com.robonix.client.domain

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.ui.geometry.Offset
import com.robonix.client.AppLog
import com.robonix.client.data.grpc.AtlasClient
import com.robonix.client.ui.perception.LidarScanPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

data class SceneObject(
    val id: String,
    val label: String,
    val x: Float,
    val y: Float,
    val width: Float = 0.5f,
    val height: Float = 0.5f,
    val yaw: Float = 0f,
)

data class SceneRegion(
    val name: String,
    val kind: String = "room",
    val colorHex: String = "#35e0a0",
    val points: List<Offset> = emptyList(),
)

data class RobotPose(
    val x: Float = 0f,
    val y: Float = 0f,
    val headingRad: Float = 0f,
    val roomName: String = "",
)

data class OccupancyGridData(
    val bitmap: Bitmap,
    val originX: Float,
    val originY: Float,
    val resolution: Float,
    val width: Int,
    val height: Int,
)

data class PerceptionDataSnapshot(
    val cameraBitmap: Bitmap? = null,
    val depthBitmap: Bitmap? = null,
    val lidarPoints: List<LidarScanPoint> = emptyList(),
    val mapBitmap: Bitmap? = null,
    val occupancyGrid: OccupancyGridData? = null,
    val robotPose: RobotPose? = null,
    val objects: List<SceneObject> = emptyList(),
    val regions: List<SceneRegion> = emptyList(),
    val isAvailable: Boolean = false,
    val latencyMs: Int = 0,
    val cameraInfo: String = "",
    val depthInfo: String = "",
    val lidarInfo: String = "",
    val sceneInfo: String = "",
)

@Singleton
class PerceptionRepository @Inject constructor(
    private val atlasClient: AtlasClient,
) {
    companion object {
        const val CONTRACT_CAMERA_RGB = "robonix/primitive/camera/snapshot"
        const val CONTRACT_CAMERA_DEPTH = "robonix/primitive/camera/depth_snapshot"
        const val CONTRACT_LIDAR = "robonix/primitive/lidar/snapshot"
        const val CONTRACT_SCENE_ROBOT = "robonix/system/scene/get_robot_context"
        const val CONTRACT_SCENE_OBJECTS = "robonix/system/scene/list_objects"
        const val CONTRACT_SCENE_REGIONS = "robonix/system/scene/list_regions"

        private const val MCP_PROTOCOL_VERSION = "2024-11-05"
        private const val MAP_UI_PORT = 50107
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .followRedirects(true)
        .addInterceptor { chain ->
            val request = chain.request()
            val response = chain.proceed(request)
            // Handle redirects (301, 302, 307, 308) where the server advertises a loopback Location header
            if (response.isRedirect) {
                val location = response.header("Location")
                if (!location.isNullOrBlank()) {
                    val originalHost = request.url.host
                    val originalPort = request.url.port
                    val locUri = java.net.URI(location)
                    val locHost = locUri.host
                    val locPort = if (locUri.port != -1) locUri.port else originalPort
                    if (locHost == "127.0.0.1" || locHost == "localhost" || locHost == "0.0.0.0") {
                        val newUrl = "${locUri.scheme ?: "http"}://$originalHost:$locPort${locUri.rawPath ?: ""}"
                        val newReq = request.newBuilder()
                            .url(newUrl)
                            .build()
                        response.close()
                        return@addInterceptor chain.proceed(newReq)
                    }
                }
            }
            response
        }
        .build()

    private data class McpSession(
        val endpoint: String,
        val hostHeader: String?,
        var sessionId: String?,
    )

    // Keyed by "$atlasEndpoint::$contractId"
    private val sessionCache = ConcurrentHashMap<String, McpSession>()

    suspend fun fetchPerceptionSnapshot(robotHost: String, atlasPort: Int = 50051): PerceptionDataSnapshot =
        withContext(Dispatchers.IO) {
            val started = System.currentTimeMillis()
            val host = robotHost.trim()
            if (host.isBlank()) {
                return@withContext PerceptionDataSnapshot()
            }
            val atlasEndpoint = "$host:$atlasPort"

            var cameraBitmap: Bitmap? = null
            var cameraInfo = ""
            var depthBitmap: Bitmap? = null
            var depthInfo = ""
            var lidarPoints = emptyList<LidarScanPoint>()
            var lidarInfo = ""
            var robotPose: RobotPose? = null
            var objects = emptyList<SceneObject>()
            var regions = emptyList<SceneRegion>()
            var occupancyGrid: OccupancyGridData? = null
            var mapBitmap: Bitmap? = null
            var anySuccess = false

            // Fetch sensors in parallel directly from the configured robot host
            coroutineScope {
                // 1. Camera RGB
                val cameraDeferred = async {
                    fetchCameraRgb(atlasEndpoint, host)
                }

                // 2. Depth Camera
                val depthDeferred = async {
                    fetchCameraDepth(atlasEndpoint, host)
                }

                // 3. LiDAR Scan
                val lidarDeferred = async {
                    fetchLidar(atlasEndpoint, host)
                }

                // 4. Scene Context
                val sceneDeferred = async {
                    fetchScene(atlasEndpoint, host)
                }

                // 5. Occupancy Map
                val mapDeferred = async {
                    fetchOccupancyMap(host)
                }

                // Await Camera
                try {
                    val res = cameraDeferred.await()
                    if (res != null) {
                        cameraBitmap = res.first
                        cameraInfo = res.second
                        anySuccess = true
                    }
                } catch (e: Exception) {
                    AppLog.write("PERCEPTION", "Camera fetch failed: ${e.message}")
                }

                // Await Depth
                try {
                    val res = depthDeferred.await()
                    if (res != null) {
                        depthBitmap = res.first
                        depthInfo = res.second
                        anySuccess = true
                    }
                } catch (e: Exception) {
                    AppLog.write("PERCEPTION", "Depth fetch failed: ${e.message}")
                }

                // Await LiDAR
                try {
                    val res = lidarDeferred.await()
                    if (res != null) {
                        lidarPoints = res.first
                        lidarInfo = res.second
                        anySuccess = true
                    }
                } catch (e: Exception) {
                    AppLog.write("PERCEPTION", "LiDAR fetch failed: ${e.message}")
                }

                // Await Scene
                try {
                    val res = sceneDeferred.await()
                    if (res != null) {
                        robotPose = res.robotPose
                        objects = res.objects
                        regions = res.regions
                        if (robotPose != null || objects.isNotEmpty() || regions.isNotEmpty()) {
                            anySuccess = true
                        }
                    }
                } catch (e: Exception) {
                    AppLog.write("PERCEPTION", "Scene fetch failed: ${e.message}")
                }

                // Await Map
                try {
                    val res = mapDeferred.await()
                    if (res != null) {
                        occupancyGrid = res
                        mapBitmap = res.bitmap
                        anySuccess = true
                    }
                } catch (e: Exception) {
                    AppLog.write("PERCEPTION", "Map fetch failed: ${e.message}")
                }
            }

            val elapsed = (System.currentTimeMillis() - started).toInt()
            val sceneParts = mutableListOf<String>()
            if (objects.isNotEmpty()) sceneParts.add("${objects.size} objects")
            if (regions.isNotEmpty()) sceneParts.add("${regions.size} regions")
            if (!robotPose?.roomName.isNullOrBlank()) sceneParts.add(robotPose!!.roomName)
            val sceneInfo = sceneParts.joinToString(" · ")

            PerceptionDataSnapshot(
                cameraBitmap = cameraBitmap,
                depthBitmap = depthBitmap,
                lidarPoints = lidarPoints,
                mapBitmap = mapBitmap,
                occupancyGrid = occupancyGrid,
                robotPose = robotPose,
                objects = objects,
                regions = regions,
                isAvailable = anySuccess,
                latencyMs = elapsed.coerceAtLeast(1),
                cameraInfo = cameraInfo,
                depthInfo = depthInfo,
                lidarInfo = lidarInfo,
                sceneInfo = sceneInfo,
            )
        }

    // ─────────────────────────────────────────────────────────────────────────────
    // Direct Sensor Fetching (Strictly using configured robot IP)
    // ─────────────────────────────────────────────────────────────────────────────

    private suspend fun fetchCameraRgb(atlasEndpoint: String, host: String): Pair<Bitmap, String>? {
        try {
            val args = JSONObject().apply {
                put("camera_name", "head_camera")
                put("format", "jpeg")
            }
            val res = mcpCall(atlasEndpoint, CONTRACT_CAMERA_RGB, "snapshot", args)
            val b64 = extractImageBase64(res)
            if (b64.isNotBlank()) {
                val bytes = Base64.decode(b64, Base64.DEFAULT)
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bmp != null) {
                    val w = res.optInt("width", bmp.width)
                    val h = res.optInt("height", bmp.height)
                    return Pair(bmp, "${w}×${h}")
                }
            }
        } catch (e: Exception) {
            AppLog.write("PERCEPTION", "Direct MCP Camera error: ${e.message}")
        }
        return null
    }

    private suspend fun fetchCameraDepth(atlasEndpoint: String, host: String): Pair<Bitmap, String>? {
        try {
            val args = JSONObject().apply {
                put("camera_name", "head_camera")
                put("format", "png")
                put("colormap", "turbo")
            }
            val res = mcpCall(atlasEndpoint, CONTRACT_CAMERA_DEPTH, "depth_snapshot", args)
            val b64 = extractImageBase64(res)
            if (b64.isNotBlank()) {
                val bytes = Base64.decode(b64, Base64.DEFAULT)
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bmp != null) {
                    val w = res.optInt("width", bmp.width)
                    val h = res.optInt("height", bmp.height)
                    return Pair(bmp, "${w}×${h}")
                }
            }
        } catch (e: Exception) {
            AppLog.write("PERCEPTION", "Direct MCP Depth error: ${e.message}")
        }
        return null
    }

    private suspend fun fetchLidar(atlasEndpoint: String, host: String): Pair<List<LidarScanPoint>, String>? {
        try {
            val args = JSONObject().apply {
                put("lidar_name", "main_lidar")
            }
            val res = mcpCall(atlasEndpoint, CONTRACT_LIDAR, "snapshot", args)
            val scan = if (res.has("scan")) res.getJSONObject("scan") else res
            val pts = parseLidarScan(scan)
            if (pts.isNotEmpty()) {
                val fov = Math.round(
                    Math.abs(
                        (scan.optDouble("angle_max", Math.PI) - scan.optDouble("angle_min", -Math.PI)) *
                            (180.0 / Math.PI)
                    )
                )
                val info = "${fov}° · ${pts.size} rays"
                return Pair(pts, info)
            }
        } catch (e: Exception) {
            AppLog.write("PERCEPTION", "Direct MCP LiDAR error: ${e.message}")
        }
        return null
    }

    private data class SceneResult(
        val robotPose: RobotPose?,
        val objects: List<SceneObject>,
        val regions: List<SceneRegion>,
    )

    private suspend fun fetchScene(atlasEndpoint: String, host: String): SceneResult {
        var robotPose: RobotPose? = null
        val objects = mutableListOf<SceneObject>()
        val regions = mutableListOf<SceneRegion>()

        // 1. Direct query to robot map HTTP service (http://$host:50107/api/state)
        try {
            val url = "http://$host:$MAP_UI_PORT/api/state"
            val req = Request.Builder().url(url).build()
            httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val json = JSONObject(resp.body?.string() ?: "")
                    val rob = json.optJSONObject("robot")
                    val pose = rob?.optJSONObject("pose")
                    if (pose != null) {
                        robotPose = RobotPose(
                            x = pose.optDouble("x", 0.0).toFloat(),
                            y = pose.optDouble("y", 0.0).toFloat(),
                            headingRad = pose.optDouble("yaw", 0.0).toFloat(),
                            roomName = rob.optString("room_name", ""),
                        )
                    } else if (rob != null && (rob.has("x") || rob.has("yaw"))) {
                        robotPose = RobotPose(
                            x = rob.optDouble("x", 0.0).toFloat(),
                            y = rob.optDouble("y", 0.0).toFloat(),
                            headingRad = rob.optDouble("yaw", 0.0).toFloat(),
                            roomName = rob.optString("room_name", ""),
                        )
                    }

                    val objArr = json.optJSONArray("objects")
                    if (objArr != null) {
                        for (i in 0 until objArr.length()) {
                            val o = objArr.optJSONObject(i) ?: continue
                            val oPose = o.optJSONObject("pose")
                            val ox = (oPose?.optDouble("x") ?: o.optDouble("x", 0.0)).toFloat()
                            val oy = (oPose?.optDouble("y") ?: o.optDouble("y", 0.0)).toFloat()
                            val oyaw = (oPose?.optDouble("yaw") ?: o.optDouble("yaw", 0.0)).toFloat()
                            objects.add(
                                SceneObject(
                                    id = o.optString("short_id", o.optString("id", "$i")),
                                    label = o.optString("cls", o.optString("label", "object")),
                                    x = ox,
                                    y = oy,
                                    width = o.optDouble("size_x", 0.5).toFloat(),
                                    height = o.optDouble("size_y", 0.5).toFloat(),
                                    yaw = oyaw,
                                )
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            AppLog.write("PERCEPTION", "Map HTTP state error: ${e.message}")
        }

        // 2. Fallback to direct MCP scene contracts if pose or objects missing
        if (robotPose == null) {
            try {
                val res = mcpCall(atlasEndpoint, CONTRACT_SCENE_ROBOT, "get_robot_context")
                val rob = res.optJSONObject("robot") ?: res
                if (rob.has("x") || rob.has("pose")) {
                    val pose = rob.optJSONObject("pose") ?: rob
                    robotPose = RobotPose(
                        x = pose.optDouble("x", 0.0).toFloat(),
                        y = pose.optDouble("y", 0.0).toFloat(),
                        headingRad = pose.optDouble("yaw", 0.0).toFloat(),
                        roomName = rob.optString("room_name", ""),
                    )
                }
            } catch (_: Exception) {}
        }

        if (objects.isEmpty()) {
            try {
                val res = mcpCall(atlasEndpoint, CONTRACT_SCENE_OBJECTS, "list_objects")
                val arr = res.optJSONArray("objects")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        objects.add(
                            SceneObject(
                                id = o.optString("id", "$i"),
                                label = o.optString("label", "object"),
                                x = o.optDouble("x", 0.0).toFloat(),
                                y = o.optDouble("y", 0.0).toFloat(),
                                width = o.optDouble("size_x", 0.5).toFloat(),
                                height = o.optDouble("size_y", 0.5).toFloat(),
                                yaw = o.optDouble("yaw", 0.0).toFloat(),
                            )
                        )
                    }
                }
            } catch (_: Exception) {}
        }

        return SceneResult(robotPose, objects, regions)
    }

    private fun fetchOccupancyMap(host: String): OccupancyGridData? {
        // 1. Direct query to robot map HTTP service (http://$host:50107/api/state)
        try {
            val url = "http://$host:$MAP_UI_PORT/api/state"
            val req = Request.Builder().url(url).build()
            httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val json = JSONObject(resp.body?.string() ?: "")
                    val occ = json.optJSONObject("occupancy") ?: json.optJSONObject("map")
                    if (occ != null) {
                        val b64 = extractImageBase64(occ)
                        if (b64.isNotBlank()) {
                            val bytes = Base64.decode(b64, Base64.DEFAULT)
                            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            if (bmp != null) {
                                val ox = occ.optDouble("origin_x", 0.0).toFloat()
                                val oy = occ.optDouble("origin_y", 0.0).toFloat()
                                val res = occ.optDouble("resolution", 0.05).toFloat()
                                val w = occ.optInt("width", bmp.width)
                                val h = occ.optInt("height", bmp.height)
                                return OccupancyGridData(bmp, ox, oy, res, w, h)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            AppLog.write("PERCEPTION", "Occupancy map HTTP state error: ${e.message}")
        }

        // 2. Direct query to :50107/map.png fallback
        try {
            val url = "http://$host:$MAP_UI_PORT/map.png"
            val req = Request.Builder().url(url).build()
            httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val bytes = resp.body?.bytes()
                    if (bytes != null && bytes.isNotEmpty()) {
                        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        if (bmp != null) {
                            return OccupancyGridData(
                                bitmap = bmp,
                                originX = -bmp.width * 0.05f / 2f,
                                originY = -bmp.height * 0.05f / 2f,
                                resolution = 0.05f,
                                width = bmp.width,
                                height = bmp.height,
                            )
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        return null
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // MCP Transport & Handshake (Strictly using configured robot host)
    // ─────────────────────────────────────────────────────────────────────────────

    private suspend fun mcpCall(
        atlasEndpoint: String,
        contractId: String,
        toolName: String? = null,
        arguments: JSONObject = JSONObject(),
    ): JSONObject {
        val cacheKey = "$atlasEndpoint::$contractId"
        var session = sessionCache[cacheKey]

        if (session == null) {
            val endpointInfo = atlasClient.discoverMcpEndpoint(atlasEndpoint, contractId)
            session = performMcpHandshake(endpointInfo.url, endpointInfo.hostHeader)
            sessionCache[cacheKey] = session
        }

        val tool = toolName ?: contractId.substringAfterLast("/")
        return try {
            callMcpTool(session, tool, arguments)
        } catch (e: Exception) {
            // Invalidate session cache on error so next call re-handshakes
            sessionCache.remove(cacheKey)
            throw e
        }
    }

    private fun performMcpHandshake(endpoint: String, hostHeader: String?): McpSession {
        val cleanEndpoint = endpoint.trimEnd('/')
        val initPayload = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", 1)
            put("method", "initialize")
            put("params", JSONObject().apply {
                put("protocolVersion", MCP_PROTOCOL_VERSION)
                put("capabilities", JSONObject())
                put("clientInfo", JSONObject().apply {
                    put("name", "robonix-client-android")
                    put("version", "1.0.0")
                })
            })
        }

        val initBuilder = Request.Builder()
            .url(cleanEndpoint)
            .post(initPayload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .header("Accept", "application/json, text/event-stream")

        if (!hostHeader.isNullOrBlank()) {
            initBuilder.header("Host", hostHeader)
        }

        var sessionId: String? = null
        httpClient.newCall(initBuilder.build()).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw RuntimeException("MCP initialize failed: HTTP ${resp.code}")
            }
            sessionId = resp.header("Mcp-Session-Id")
        }

        // Send notifications/initialized
        try {
            val notifPayload = JSONObject().apply {
                put("jsonrpc", "2.0")
                put("method", "notifications/initialized")
            }
            val notifBuilder = Request.Builder()
                .url(cleanEndpoint)
                .post(notifPayload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .header("Accept", "application/json, text/event-stream")

            if (!hostHeader.isNullOrBlank()) {
                notifBuilder.header("Host", hostHeader)
            }
            if (sessionId != null) {
                notifBuilder.header("Mcp-Session-Id", sessionId!!)
            }
            httpClient.newCall(notifBuilder.build()).execute().close()
        } catch (_: Exception) {}

        return McpSession(endpoint = cleanEndpoint, hostHeader = hostHeader, sessionId = sessionId)
    }

    private fun callMcpTool(session: McpSession, tool: String, arguments: JSONObject): JSONObject {
        val cleanEndpoint = session.endpoint.trimEnd('/')
        val payload = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", 2)
            put("method", "tools/call")
            put("params", JSONObject().apply {
                put("name", tool)
                put("arguments", arguments)
            })
        }

        val builder = Request.Builder()
            .url(cleanEndpoint)
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .header("Accept", "application/json, text/event-stream")

        if (!session.hostHeader.isNullOrBlank()) {
            builder.header("Host", session.hostHeader)
        }
        if (session.sessionId != null) {
            builder.header("Mcp-Session-Id", session.sessionId!!)
        }

        val body = httpClient.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw RuntimeException("MCP tools/call $tool failed: HTTP ${resp.code}")
            }
            resp.body?.string() ?: "{}"
        }

        return parseMcpResult(body, tool)
    }

    private fun parseMcpResult(body: String, tool: String): JSONObject {
        // Body can be SSE (data: {...}) or raw JSON
        val messages = mutableListOf<JSONObject>()

        if (body.contains("data:")) {
            // Parse SSE chunks
            val blocks = body.split("\n\n")
            for (block in blocks) {
                for (line in block.lines()) {
                    val trimmed = line.trim()
                    if (trimmed.startsWith("data:")) {
                        val jsonStr = trimmed.removePrefix("data:").trim()
                        if (jsonStr.isNotBlank()) {
                            try {
                                messages.add(JSONObject(jsonStr))
                            } catch (_: Exception) {}
                        }
                    }
                }
            }
        } else {
            try {
                messages.add(JSONObject(body.trim()))
            } catch (_: Exception) {}
        }

        for (msg in messages) {
            val result = msg.optJSONObject("result") ?: if (msg.has("content")) msg else null
            if (result != null) {
                if (result.optBoolean("isError", false)) {
                    throw RuntimeException("MCP $tool isError: ${result.opt("content")}")
                }
                val content = result.optJSONArray("content")
                if (content != null) {
                    for (i in 0 until content.length()) {
                        val item = content.optJSONObject(i) ?: continue
                        val type = item.optString("type")
                        if (type == "text") {
                            val text = item.optString("text", "")
                            return try {
                                JSONObject(text)
                            } catch (_: Exception) {
                                JSONObject().put("text", text)
                            }
                        } else if (type == "image") {
                            return item
                        }
                    }
                }
                val structured = result.optJSONObject("structuredContent")
                if (structured != null) return structured
                return result
            }
        }

        throw RuntimeException("MCP $tool: no valid result in response")
    }

    private fun extractImageBase64(json: JSONObject): String {
        if (json.has("data")) {
            val d = json.optString("data")
            if (d.isNotBlank()) return cleanBase64(d)
        }
        if (json.has("png_b64")) {
            val d = json.optString("png_b64")
            if (d.isNotBlank()) return cleanBase64(d)
        }
        if (json.has("image_base64")) {
            val d = json.optString("image_base64")
            if (d.isNotBlank()) return cleanBase64(d)
        }
        val img = json.optJSONObject("image")
        if (img != null) {
            val d = extractImageBase64(img)
            if (d.isNotBlank()) return d
        }
        val occ = json.optJSONObject("occupancy")
        if (occ != null) {
            val d = extractImageBase64(occ)
            if (d.isNotBlank()) return d
        }
        return ""
    }

    private fun cleanBase64(raw: String): String {
        var s = raw.trim()
        if (s.startsWith("data:image", ignoreCase = true) && s.contains(",")) {
            s = s.substringAfter(",")
        }
        return s.replace("\n", "").replace("\r", "").trim()
    }

    private fun parseLidarScan(scan: JSONObject): List<LidarScanPoint> {
        val ranges = scan.optJSONArray("ranges") ?: return emptyList()
        val angleMin = scan.optDouble("angle_min", -Math.PI)
        val angleInc = scan.optDouble("angle_increment", 0.017)
        val rangeMin = scan.optDouble("range_min", 0.05).toFloat()
        val rangeMax = scan.optDouble("range_max", 12.0).toFloat()

        val list = ArrayList<LidarScanPoint>(ranges.length())
        for (i in 0 until ranges.length()) {
            val r = ranges.optDouble(i, 0.0).toFloat()
            if (r in rangeMin..rangeMax && !r.isNaN() && !r.isInfinite()) {
                val angleRad = angleMin + i * angleInc
                val angleDeg = Math.toDegrees(angleRad).toFloat()
                list.add(LidarScanPoint(angleDeg, r, 1.0f))
            }
        }
        return list
    }
}
