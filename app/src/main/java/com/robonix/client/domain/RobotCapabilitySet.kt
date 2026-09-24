package com.robonix.client.domain

/**
 * Describes the perception contracts actually advertised by a connected robot body.
 *
 * Built once per Atlas endpoint via [PerceptionRepository.discoverCapabilities] and cached
 * for the lifetime of that connection. Each field is null when the robot has no provider
 * for that sensor type, which causes the corresponding fetch to be skipped silently.
 *
 * Adding support for a new body type only requires extending the candidate lists in
 * [PerceptionRepository.Candidates] — no changes needed here or in the UI layer.
 */
data class RobotCapabilitySet(
    /** MCP contract for a one-shot RGB image (e.g. "robonix/primitive/camera/snapshot"). */
    val cameraRgb: String?,

    /** MCP contract for a one-shot depth image (e.g. "robonix/primitive/camera/depth_snapshot"). */
    val cameraDepth: String?,

    /**
     * MCP contract for a one-shot lidar scan.
     * - 2D lidar / Webots: "robonix/primitive/lidar/snapshot"
     * - 3D lidar / MID-360: "robonix/primitive/lidar/lidar_snapshot"
     */
    val lidarSnapshot: String?,

    /** MCP contract to read the robot's current pose in the scene. */
    val sceneRobot: String?,

    /** MCP contract to list semantic scene objects. */
    val sceneObjects: String?,

    /** MCP contract to list scene regions / rooms. */
    val sceneRegions: String?,
) {
    /** True if the robot has at least one camera or lidar sensor available. */
    val hasAnyPerception: Boolean
        get() = cameraRgb != null || cameraDepth != null || lidarSnapshot != null

    /** True if the robot exposes any scene-awareness capability. */
    val hasSceneContext: Boolean
        get() = sceneRobot != null || sceneObjects != null

    companion object {
        /** An empty set used when capability discovery fails — all fetches will be skipped. */
        val EMPTY = RobotCapabilitySet(
            cameraRgb = null,
            cameraDepth = null,
            lidarSnapshot = null,
            sceneRobot = null,
            sceneObjects = null,
            sceneRegions = null,
        )
    }
}
