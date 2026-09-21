package com.robonix.client.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.AltRoute
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.robonix.client.data.model.*
import com.robonix.client.domain.RtdlStateHolder
import com.robonix.client.domain.SystemRepository
import com.robonix.client.ui.components.CodeBlockView
import com.robonix.client.ui.components.CyberCard
import com.robonix.client.ui.components.PulsingStatusDot
import com.robonix.client.ui.i18n.t
import com.robonix.client.ui.i18n.tStatus
import com.robonix.client.ui.navigation.SharedViewModel
import com.robonix.client.ui.theme.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Flattened representation of a behavior tree node to eliminate recursion crashes. */
data class FlattenedTreeNode(
    val node: RtdlNode,
    val depth: Int,
    val hasChildren: Boolean,
    val status: String,
    val stateObj: RtdlNodeState?,
)

data class RtdlUiState(
    val planRecords: List<PlanRecord> = emptyList(),
    val executorPlans: List<ExecutorPlan> = emptyList(),
    val executorPlansReady: Boolean = false,
    val isRefreshing: Boolean = false,
    val selectedTab: Int = 0, // 0 = 活跃执行, 1 = 决策行为树
    val selectedRecordIndex: Int = 0,
    val expandedNodeIndices: Set<Int> = emptySet(),
    val livePlan: RtdlPlan? = null,
    val liveNodeStates: Map<Int, RtdlNodeState> = emptyMap(),
    val error: String? = null,
)

@HiltViewModel
class RtdlViewModel @Inject constructor(
    private val systemRepository: SystemRepository,
    private val rtdlState: RtdlStateHolder,
) : ViewModel() {

    private val _uiState = MutableStateFlow(RtdlUiState())
    val uiState: StateFlow<RtdlUiState> = _uiState.asStateFlow()

    private var pollJob: Job? = null

    init {
        // Sync live plan and records from RtdlStateHolder
        viewModelScope.launch {
            rtdlState.livePlan.collect { p -> _uiState.update { it.copy(livePlan = p) } }
        }
        viewModelScope.launch {
            rtdlState.liveNodeStates.collect { ns -> _uiState.update { it.copy(liveNodeStates = ns) } }
        }
        viewModelScope.launch {
            rtdlState.planRecords.collect { recs ->
                _uiState.update {
                    it.copy(
                        planRecords = recs,
                        selectedRecordIndex = if (recs.isNotEmpty()) it.selectedRecordIndex.coerceIn(0, recs.size - 1) else 0,
                    )
                }
            }
        }
    }

    fun startPolling(target: String) {
        pollJob?.cancel()
        if (target.isBlank()) return
        pollJob = viewModelScope.launch {
            while (isActive) {
                fetchActivePlans(target, isManual = false)
                val hasActive = _uiState.value.executorPlans.isNotEmpty()
                delay(if (hasActive) 3000L else 10000L)
            }
        }
    }

    fun stopPolling() {
        pollJob?.cancel()
    }

    fun refresh(target: String) {
        if (target.isBlank()) return
        viewModelScope.launch {
            fetchActivePlans(target, isManual = true)
        }
    }

    private suspend fun fetchActivePlans(target: String, isManual: Boolean) {
        if (isManual) _uiState.update { it.copy(isRefreshing = true, error = null) }
        try {
            val plans = systemRepository.getActivePlans(target)
            _uiState.update {
                it.copy(
                    executorPlans = plans,
                    executorPlansReady = true,
                    isRefreshing = false,
                    error = null,
                )
            }
        } catch (e: Exception) {
            _uiState.update {
                it.copy(
                    executorPlansReady = false,
                    isRefreshing = false,
                    error = e.message,
                )
            }
        }
    }

    fun selectTab(tab: Int) {
        _uiState.update { it.copy(selectedTab = tab) }
    }

    fun selectRecordIndex(index: Int) {
        _uiState.update { it.copy(selectedRecordIndex = index, expandedNodeIndices = emptySet()) }
    }

    fun toggleNodeExpanded(nodeIndex: Int) {
        _uiState.update { state ->
            val set = state.expandedNodeIndices.toMutableSet()
            if (set.contains(nodeIndex)) set.remove(nodeIndex) else set.add(nodeIndex)
            state.copy(expandedNodeIndices = set)
        }
    }
}

@Composable
fun RtdlScreen(
    viewModel: RtdlViewModel = hiltViewModel(),
    sharedViewModel: SharedViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val settings by sharedViewModel.settings.collectAsState()

    LaunchedEffect(settings.atlasEndpoint) {
        if (settings.atlasEndpoint.isNotBlank()) {
            viewModel.startPolling(settings.atlasEndpoint)
        }
    }

    DisposableEffect(Unit) {
        onDispose { viewModel.stopPolling() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        // Top Section: Segmented Pill Tab Bar & Refresh button
        RtdlTopBar(
            selectedTab = state.selectedTab,
            activeCount = state.executorPlans.size,
            historyCount = state.planRecords.size,
            isRefreshing = state.isRefreshing,
            onSelectTab = { viewModel.selectTab(it) },
            onRefresh = { viewModel.refresh(settings.atlasEndpoint) },
        )

        Spacer(Modifier.height(10.dp))

        if (state.error != null) {
            RtdlErrorBanner(error = state.error!!)
            Spacer(Modifier.height(8.dp))
        }

        // Tab Content
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (state.selectedTab) {
                0 -> ActivePlansSection(state = state)
                1 -> BehaviorTreeSection(
                    state = state,
                    onSelectRecord = { viewModel.selectRecordIndex(it) },
                    onToggleNode = { viewModel.toggleNodeExpanded(it) },
                )
            }
        }
    }
}

/** Segmented Pill Selector + Action Row */
@Composable
private fun RtdlTopBar(
    selectedTab: Int,
    activeCount: Int,
    historyCount: Int,
    isRefreshing: Boolean,
    onSelectTab: (Int) -> Unit,
    onRefresh: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Segmented Pills
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(Panel2)
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            RtdlTabPill(
                title = t("rtdl.tab.active", activeCount),
                icon = Icons.Default.PlayArrow,
                isSelected = selectedTab == 0,
                onClick = { onSelectTab(0) },
            )
            RtdlTabPill(
                title = t("rtdl.tab.history", historyCount),
                icon = Icons.Default.AccountTree,
                isSelected = selectedTab == 1,
                onClick = { onSelectTab(1) },
            )
        }

        // Refresh Button with smooth rotation / alpha
        IconButton(
            onClick = onRefresh,
            modifier = Modifier.size(32.dp),
            enabled = !isRefreshing,
        ) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = t("action.refresh"),
                tint = if (isRefreshing) Amber else Cyan,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun RtdlTabPill(
    title: String,
    icon: ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        color = if (isSelected) Cyan.copy(alpha = 0.18f) else Color.Transparent,
        shape = RoundedCornerShape(16.dp),
        border = if (isSelected) BorderStroke(1.dp, Cyan.copy(alpha = 0.5f)) else null,
        modifier = Modifier.clickable { onClick() },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isSelected) Cyan else Muted,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = title,
                color = if (isSelected) Text else Muted,
                fontSize = 12.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            )
        }
    }
}

// -----------------------------------------------------------------------------------------
// Tab 0: Active Executor Plans
// -----------------------------------------------------------------------------------------

@Composable
private fun ActivePlansSection(state: RtdlUiState) {
    if (!state.executorPlansReady && state.isRefreshing) {
        RtdlLoadingState()
        return
    }

    if (state.executorPlans.isEmpty()) {
        RtdlEmptyState(
            title = t("rtdl.active.empty"),
            subtitle = "当前后端执行器空闲，待命接收导航、操作与感知任务",
            icon = Icons.Default.DoneAll,
        )
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(state.executorPlans, key = { it.planId }) { plan ->
            ExecutorPlanCyberCard(plan)
        }
    }
}

@Composable
private fun ExecutorPlanCyberCard(plan: ExecutorPlan) {
    CyberCard(
        modifier = Modifier.fillMaxWidth(),
        borderColor = if (plan.cancelled) Red.copy(alpha = 0.5f) else Cyan.copy(alpha = 0.3f),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = plan.description.ifBlank { plan.planId },
                        color = Text,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "ID: ${plan.planId} · 共 ${plan.opCount} 个算子",
                        color = Dim,
                        fontSize = 10.5.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                Surface(
                    color = if (plan.cancelled) Red.copy(alpha = 0.15f) else Green.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, if (plan.cancelled) Red.copy(alpha = 0.4f) else Green.copy(alpha = 0.4f)),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (!plan.cancelled) {
                            PulsingStatusDot(color = Green, size = 5.dp)
                            Spacer(Modifier.width(5.dp))
                        }
                        Text(
                            text = if (plan.cancelled) "已取消" else "执行中",
                            color = if (plan.cancelled) Red else Green,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // Sequence of Ops
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                plan.ops.forEachIndexed { index, op ->
                    ExecutorOpRow(index = index + 1, op = op)
                }
            }
        }
    }
}

@Composable
private fun ExecutorOpRow(index: Int, op: ExecutorOp) {
    val state = op.state.uppercase()
    val isRunning = state in listOf("RUNNING", "ACTIVE")
    val isDone = state in listOf("SUCCEEDED", "SUCCESS", "DONE")
    val isFailed = state in listOf("FAILED", "ERROR")

    val stateColor = when {
        isRunning -> Amber
        isDone -> Green
        isFailed -> Red
        else -> Muted
    }

    Surface(
        color = Panel2,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(0.8.dp, if (isRunning) Amber.copy(alpha = 0.5f) else LineSoft),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    color = LineSoft,
                    shape = RoundedCornerShape(4.dp),
                ) {
                    Text(
                        text = "#$index",
                        color = Cyan,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(
                        text = op.description.ifBlank { op.opId },
                        color = Text,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${op.providerId} · ${op.contractId}",
                        color = Dim,
                        fontSize = 9.5.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Surface(
                color = stateColor.copy(alpha = 0.12f),
                shape = RoundedCornerShape(8.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (isRunning) {
                        PulsingStatusDot(color = Amber, size = 4.dp)
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        text = tStatus(state),
                        color = stateColor,
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

// -----------------------------------------------------------------------------------------
// Tab 1: Behavior Tree & Execution History
// -----------------------------------------------------------------------------------------

@Composable
private fun BehaviorTreeSection(
    state: RtdlUiState,
    onSelectRecord: (Int) -> Unit,
    onToggleNode: (Int) -> Unit,
) {
    // Current active plan from record list or live plan
    val selectedRecord = state.planRecords.getOrNull(state.selectedRecordIndex)
    val plan = selectedRecord?.plan ?: state.livePlan
    val nodeStates = selectedRecord?.nodeStates ?: state.liveNodeStates

    if (plan == null || plan.nodes.isEmpty()) {
        RtdlEmptyState(
            title = t("rtdl.history.empty"),
            subtitle = "对话产生任务后，此处将自动绘制完整的 RTDL 行为决策树",
            icon = Icons.Default.AccountTree,
        )
        return
    }

    // Safely flatten the behavior tree into non-recursive linear items
    val flattenedNodes = remember(plan, nodeStates) {
        flattenPlanTree(plan, nodeStates)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Record Selector Chips (if multiple rounds exist)
        if (state.planRecords.size > 1) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    state.planRecords.forEachIndexed { i, record ->
                        val isSelected = i == state.selectedRecordIndex
                        Surface(
                            color = if (isSelected) Cyan.copy(alpha = 0.2f) else Panel2,
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, if (isSelected) Cyan else LineSoft),
                            modifier = Modifier.clickable { onSelectRecord(i) },
                        ) {
                            Text(
                                text = "第 ${record.plan.round} 轮 (${record.plan.nodes.size} 节点)",
                                color = if (isSelected) Cyan else Text,
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            )
                        }
                    }
                }
            }
        }

        // Summary Card
        item {
            BehaviorTreeSummaryCard(plan = plan, nodeStates = nodeStates)
        }

        // Flattened Node Items
        items(flattenedNodes, key = { it.node.index }) { item ->
            FlattenedNodeCard(
                item = item,
                isExpanded = state.expandedNodeIndices.contains(item.node.index),
                onToggleExpand = { onToggleNode(item.node.index) },
            )
        }

        item { Spacer(Modifier.height(16.dp)) }
    }
}

/** Pure, non-recursive traversal helper that guarantees no StackOverflowError. */
private fun flattenPlanTree(
    plan: RtdlPlan,
    nodeStates: Map<Int, RtdlNodeState>,
): List<FlattenedTreeNode> {
    val nodes = plan.nodes
    if (nodes.isEmpty()) return emptyList()

    val byIndex = nodes.associateBy { it.index }
    val childSet = nodes.flatMap { it.children }.toSet()

    // Find root nodes
    val roots = mutableListOf<RtdlNode>()
    if (plan.rootIndex in byIndex) {
        roots.add(byIndex[plan.rootIndex]!!)
    }
    nodes.forEach { node ->
        if (node.index !in childSet && node !in roots) roots.add(node)
    }
    if (roots.isEmpty()) roots.add(nodes.first())

    val result = mutableListOf<FlattenedTreeNode>()
    val visited = mutableSetOf<Int>()

    // Iterative depth-first traversal using an explicit stack
    data class StackFrame(val node: RtdlNode, val depth: Int)
    val stack = ArrayDeque<StackFrame>()
    roots.reversed().forEach { stack.add(StackFrame(it, 0)) }

    while (stack.isNotEmpty()) {
        val (node, depth) = stack.removeLast()
        if (!visited.add(node.index)) continue

        val state = nodeStates[node.index]
        val status = state?.state ?: "PENDING"
        val hasChildren = node.children.any { it in byIndex }

        result.add(
            FlattenedTreeNode(
                node = node,
                depth = depth.coerceAtMost(8),
                hasChildren = hasChildren,
                status = status,
                stateObj = state,
            )
        )

        // Push children in reverse order so leftmost child is processed first
        val childNodes = node.children.mapNotNull { byIndex[it] }
        childNodes.reversed().forEach { child ->
            stack.add(StackFrame(child, depth + 1))
        }
    }

    return result
}

@Composable
private fun BehaviorTreeSummaryCard(
    plan: RtdlPlan,
    nodeStates: Map<Int, RtdlNodeState>,
) {
    val total = plan.nodes.size
    val succeeded = plan.nodes.count { nodeStates[it.index]?.state == "SUCCEEDED" }
    val running = plan.nodes.count { nodeStates[it.index]?.state == "RUNNING" }
    val failed = plan.nodes.count { nodeStates[it.index]?.state in listOf("FAILED", "ERROR") }

    CyberCard(
        modifier = Modifier.fillMaxWidth(),
        borderColor = LineSoft,
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.AccountTree, null, tint = Cyan, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "计划 ID: ${plan.planId.take(16)}",
                        color = Text,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Surface(
                    color = Cyan.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(0.8.dp, Cyan.copy(alpha = 0.4f)),
                ) {
                    Text(
                        text = "第 ${plan.round} 轮",
                        color = Cyan,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            // Progress Metrics Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "$succeeded / $total 节点已完成",
                    color = Muted,
                    fontSize = 11.sp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (running > 0) MetricTag(count = running, label = "运行中", color = Amber)
                    if (succeeded > 0) MetricTag(count = succeeded, label = "成功", color = Green)
                    if (failed > 0) MetricTag(count = failed, label = "失败", color = Red)
                }
            }

            Spacer(Modifier.height(6.dp))

            // Custom Linear Progress Bar (No BOM crash)
            val progress = if (total > 0) succeeded.toFloat() / total.toFloat() else 0f
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Panel2),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction = progress.coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (failed > 0) Amber else Cyan),
                )
            }
        }
    }
}

@Composable
private fun MetricTag(count: Int, label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.width(3.dp))
        Text(
            text = "$count $label",
            color = color,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun FlattenedNodeCard(
    item: FlattenedTreeNode,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
) {
    val node = item.node
    val status = item.status.uppercase()
    val isRunning = status in listOf("RUNNING", "ACTIVE")
    val isSucceeded = status in listOf("SUCCEEDED", "SUCCESS")
    val isFailed = status in listOf("FAILED", "ERROR", "TIMEOUT")

    val statusColor = when {
        isRunning -> Amber
        isSucceeded -> Green
        isFailed -> Red
        else -> Muted
    }

    val nodeIcon = when (node.kind.lowercase()) {
        "action" -> Icons.Default.PlayArrow
        "sequence" -> Icons.AutoMirrored.Filled.AltRoute
        "fallback", "selector" -> Icons.Default.CallSplit
        "condition" -> Icons.Default.CheckCircleOutline
        else -> Icons.Default.Lens
    }

    val actionName = node.call?.name
        ?: node.opId.ifBlank { node.description.ifBlank { node.kind } }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (item.depth * 14).dp),
    ) {
        Surface(
            color = Panel,
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(
                width = if (isRunning) 1.2.dp else 0.8.dp,
                color = if (isRunning) Amber.copy(alpha = 0.6f) else LineSoft,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggleExpand() },
        ) {
            Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = nodeIcon,
                            contentDescription = null,
                            tint = if (isRunning) Amber else Cyan,
                            modifier = Modifier.size(15.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "#${node.index} $actionName",
                            color = Text,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            color = statusColor.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(6.dp),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (isRunning) {
                                    PulsingStatusDot(color = Amber, size = 4.dp)
                                    Spacer(Modifier.width(4.dp))
                                }
                                Text(
                                    text = tStatus(status),
                                    color = statusColor,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = null,
                            tint = Dim,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }

                // Inline Accordion Inspector Details
                AnimatedVisibility(visible = isExpanded) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                    ) {
                        HorizontalDivider(color = LineSoft, thickness = 0.6.dp)
                        Spacer(Modifier.height(6.dp))

                        // Type & Provider
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = "类型: ${node.kind}",
                                color = Dim,
                                fontSize = 10.sp,
                            )
                            if (node.call != null) {
                                Text(
                                    text = "${node.call.providerId} · ${node.call.contractId}",
                                    color = Cyan,
                                    fontSize = 9.5.sp,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                        }

                        // Call Args JSON
                        if (node.call != null && node.call.args.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = "调用参数 (Args):",
                                color = Muted,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(Modifier.height(2.dp))
                            CodeBlockView(
                                code = node.call.args.entries.joinToString(separator = "\n", prefix = "{\n", postfix = "\n}") { (k, v) ->
                                    "  \"$k\": $v"
                                },
                                language = "JSON",
                            )
                        }

                        // Execution Result
                        if (item.stateObj?.leafResult != null) {
                            val res = item.stateObj.leafResult
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = "执行结果 (Result · ${if (res.success) "成功" else "失败"}):",
                                color = if (res.success) Green else Red,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(Modifier.height(2.dp))
                            val resultText = res.output.ifBlank { res.error }
                            if (resultText.isNotBlank()) {
                                CodeBlockView(code = resultText, language = "RESULT")
                            }
                        }
                    }
                }
            }
        }
    }
}

// -----------------------------------------------------------------------------------------
// Feedback / Empty States
// -----------------------------------------------------------------------------------------

@Composable
private fun RtdlLoadingState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PulsingStatusDot(color = Cyan, size = 12.dp)
        Spacer(Modifier.height(12.dp))
        Text(
            text = t("rtdl.loading"),
            color = Muted,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun RtdlEmptyState(
    title: String,
    subtitle: String,
    icon: ImageVector,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 64.dp, start = 24.dp, end = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            color = Panel2,
            shape = CircleShape,
            border = BorderStroke(1.dp, LineSoft),
            modifier = Modifier.size(56.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = Dim,
                    modifier = Modifier.size(26.dp),
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(
            text = title,
            color = Text,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = subtitle,
            color = Dim,
            fontSize = 11.5.sp,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}

@Composable
private fun RtdlErrorBanner(error: String) {
    Surface(
        color = Red.copy(alpha = 0.12f),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, Red.copy(alpha = 0.3f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.ErrorOutline, null, tint = Red, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                text = error,
                color = Red,
                fontSize = 11.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
