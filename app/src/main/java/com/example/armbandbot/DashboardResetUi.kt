package com.heyheyon.armbandbot

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.heyheyon.armbandbot.ui.LocalIsDarkMode
import com.heyheyon.armbandbot.ui.botColors
import java.io.File
import java.util.concurrent.Callable

@Composable
internal fun DashboardResetChooser(
    bots: List<DashboardScopeBot>,
    database: AppDatabase?,
    dependencies: DashboardLoaderDependencies,
    onDismiss: () -> Unit,
    onConfirm: (FrozenDashboardReset) -> Unit,
) {
    val colors = botColors(LocalIsDarkMode.current)
    var inventory by remember { mutableStateOf<List<DashboardScopeOption>?>(null) }
    var inventoryError by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    // Independent of search/general publication. Closing the chooser cancels this read;
    // reopening starts a fresh read. All state stays in this child, not the large screen closure.
    LaunchedEffect(database, attempt) {
        inventory = null
        inventoryError = false
        try {
            inventory = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                loadDashboardResetInventory(bots) { dependencies.resetHistoryScopeIds(checkNotNull(database).postDao()) }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            inventoryError = true
        }
    }
    var all by remember { mutableStateOf<Boolean?>(null) }
    var chosen by remember { mutableStateOf<Set<String>>(emptySet()) }
    AlertDialog(
        modifier = Modifier.testTag("db-reset-chooser"), onDismissRequest = onDismiss,
        containerColor = colors.dialogBg, titleContentColor = colors.text, textContentColor = colors.text,
        title = { Text("초기화할 DB 선택") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("목록의 조회 필터와 관계없이 초기화할 DB를 직접 선택하세요.", color = colors.text)
            RecordFilterCheckbox("전체 DB", "db-reset-all", all == true, colors) { all = true }
            RecordFilterCheckbox("선택한 DB", "db-reset-selected", all == false, colors) { all = false }
            if (inventoryError) {
                Text("DB 목록을 불러오지 못했습니다. 다시 시도해 주세요.", Modifier.testTag("db-reset-inventory-error"), color = colors.text)
                TextButton(modifier = Modifier.testTag("db-reset-inventory-retry"),
                    colors = ButtonDefaults.textButtonColors(contentColor = colors.iconTint),
                    onClick = { inventory = null; inventoryError = false; attempt++ }) { Text("다시 시도") }
            } else if (inventory == null) {
                Text("DB 목록을 확인하고 있습니다…", Modifier.testTag("db-reset-inventory-loading"), color = colors.subText)
            }
            if (all == false) inventory.orEmpty().forEach { option ->
                val id = (option.scope as DashboardRecordScope.Exact).scopeId
                RecordFilterCheckbox(option.label, "db-reset-scope-$id", id in chosen, colors) {
                    chosen = if (id in chosen) chosen - id else chosen + id
                }
            }
        } },
        confirmButton = { TextButton(modifier = Modifier.testTag("db-reset-next"), enabled = inventory != null && !inventoryError && (all == true || (all == false && chosen.isNotEmpty())),
            colors = ButtonDefaults.textButtonColors(contentColor = colors.iconTint), onClick = {
                val readyInventory = inventory
                if (readyInventory != null && !inventoryError && (all == true || (all == false && chosen.isNotEmpty()))) {
                    val labels = readyInventory.associate { (it.scope as DashboardRecordScope.Exact).scopeId to it.label }
                    val target = freezeDashboardResetTarget(all == true, chosen.intersect(labels.keys), labels.keys)
                    onConfirm(FrozenDashboardReset(target.scopeIds, target.allDatabases, labels))
                }
            }) { Text("다음") } },
        dismissButton = { TextButton(onClick = onDismiss, colors = ButtonDefaults.textButtonColors(contentColor = colors.subText)) { Text("취소") } },
    )
}

@Composable
internal fun DashboardResetConfirmation(target: FrozenDashboardReset, onDismiss: () -> Unit, onReset: () -> Unit) {
    val colors = botColors(LocalIsDarkMode.current)
    AlertDialog(onDismissRequest = onDismiss, modifier = Modifier.testTag("db-reset-confirmation"),
        containerColor = colors.dialogBg, titleContentColor = colors.text, textContentColor = colors.text,
        title = { Text("DB 초기화") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(if (target.allDatabases) "전체 DB 기록을 초기화할까요?" else "선택한 DB 기록을 초기화할까요?", color = colors.text)
            Text(target.scopeIds.joinToString("\n") { target.labels[it] ?: it }, modifier = Modifier.testTag("db-reset-frozen-targets"), color = colors.text)
            Text("위 DB의 검사·조치·보류 기록과 참조되지 않는 스냅샷을 삭제합니다.\n다른 DB와 중복 조치 방지 정보는 보존됩니다.\n삭제된 보류 대상은 다시 보류될 수 있습니다. 실행 중인 봇을 모두 중지한 뒤 초기화하세요.", color = colors.text)
        } },
        confirmButton = { TextButton(onClick = onReset, colors = ButtonDefaults.textButtonColors(contentColor = colors.iconTint)) { Text("초기화") } },
        dismissButton = { TextButton(onClick = onDismiss, colors = ButtonDefaults.textButtonColors(contentColor = colors.subText)) { Text("취소") } },
    )
}

/** Commit all row removals together, then prune only unreferenced snapshot pairs. Claims stay global. */
internal fun resetDashboardRecords(database: AppDatabase, root: File, target: FrozenDashboardReset, mayReset: () -> Boolean = { !BotService.hasUnfinishedDatabaseWork() }) =
    GlobalBotState.withDatabaseMaintenanceLock {
        check(mayReset()) { "실행 중이거나 종료 처리 중인 봇이 있습니다. 모두 중지한 뒤 다시 시도하세요." }
        deleteSnapshotRecordsAndFiles(database.postDao(), listOf(root)) { dao ->
            database.runInTransaction(Callable<List<String?>> {
                val posts = dao.getAllPostsForBackupMerge().filter { it.scopeId in target.scopeIds }
                val actions = dao.getAllBlockHistoryForBackupMerge().filter { it.scopeId in target.scopeIds }
                val holds = dao.getAllHoldHistoryForBackupMerge().filter { it.scopeId in target.scopeIds }
                target.scopeIds.forEach(dao::deletePostsForScope)
                actions.forEach { dao.deleteBlockHistoryById(it.id) }
                holds.forEach { dao.deleteHoldHistoryById(it.id) }
                posts.map { it.snapshotPath } + actions.map { it.snapshotPath } + holds.map { it.snapshotPath }
            })
        }
    }
