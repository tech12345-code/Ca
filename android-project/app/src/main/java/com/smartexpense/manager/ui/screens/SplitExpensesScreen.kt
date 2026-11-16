package com.smartexpense.manager.ui.screens

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.GroupAdd
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.smartexpense.manager.domain.calculator.ParsedUpiUri
import com.smartexpense.manager.domain.calculator.SettlementAction
import com.smartexpense.manager.domain.calculator.SplitExpenseCalculator
import com.smartexpense.manager.domain.model.ExpenseCategory
import com.smartexpense.manager.domain.model.GroupExpense
import com.smartexpense.manager.domain.model.GroupInvitation
import com.smartexpense.manager.domain.model.GroupMember
import com.smartexpense.manager.domain.model.GroupMemberStatus
import com.smartexpense.manager.domain.model.GroupType
import com.smartexpense.manager.domain.model.InvitationStatus
import com.smartexpense.manager.domain.model.InviteMethod
import com.smartexpense.manager.domain.model.Settlement
import com.smartexpense.manager.domain.model.SettlementDueCategory
import com.smartexpense.manager.domain.model.SettlementPaymentMethod
import com.smartexpense.manager.domain.model.SettlementStatus
import com.smartexpense.manager.domain.model.SimplifiedDebt
import com.smartexpense.manager.domain.model.SplitGroup
import com.smartexpense.manager.domain.model.SplitType
import com.smartexpense.manager.domain.model.UserProfile
import com.smartexpense.manager.ui.theme.SmartColors
import com.smartexpense.manager.ui.viewmodel.MainViewModel
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale
import java.util.concurrent.Executors

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SplitExpensesScreen(
    viewModel: MainViewModel,
    profile: UserProfile
) {
    val context = LocalContext.current
    val groups by viewModel.splitGroups.collectAsStateWithLifecycle()
    val members by viewModel.groupMembers.collectAsStateWithLifecycle()
    val invitations by viewModel.groupInvitations.collectAsStateWithLifecycle()
    val groupExpenses by viewModel.groupExpenses.collectAsStateWithLifecycle()
    val settlements by viewModel.settlements.collectAsStateWithLifecycle()
    val summary by viewModel.splitWorkspaceSummary.collectAsStateWithLifecycle()
    val receiptDraft by viewModel.receiptDraft.collectAsStateWithLifecycle()

    val currency = profile.currencySymbol
    val myAppCode = remember(profile) {
        profile.appUserCode.ifBlank { SplitExpenseCalculator().generateAppUserCode(profile.uid) }
    }
    val todayDate = remember { LocalDate.now().toString() }

    var selectedGroupId by remember { mutableStateOf<String?>(null) }
    var showCreateGroupDialog by remember { mutableStateOf(false) }
    var showJoinGroupDialog by remember { mutableStateOf(false) }
    var showInviteDialog by remember { mutableStateOf(false) }
    var showExpenseDialog by remember { mutableStateOf(false) }
    var editingExpense by remember { mutableStateOf<GroupExpense?>(null) }
    var activeSettlementId by remember { mutableStateOf<String?>(null) }
    var showQrScannerDialog by remember { mutableStateOf(false) }
    var dueFilter by remember { mutableStateOf<SettlementDueCategory?>(null) }
    var upiInput by remember(profile.upiId) { mutableStateOf(profile.upiId) }

    val selectedGroup = remember(groups, selectedGroupId) {
        groups.firstOrNull { it.id == selectedGroupId }
    }
    val activeSettlement = remember(settlements, activeSettlementId) {
        settlements.firstOrNull { it.id == activeSettlementId }
    }

    val pendingIncomingInvitations = remember(invitations, profile.uid) {
        invitations.filter { it.status == InvitationStatus.PENDING && it.inviterUid != profile.uid }
    }

    if (selectedGroup == null) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(SmartColors.Background)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("SPLIT EXPENSES", style = MaterialTheme.typography.headlineLarge)
                        Text(
                            text = "Shared groups, fair split math & dual-party UPI settlements",
                            color = SmartColors.TextMuted,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        IconButton(onClick = { showQrScannerDialog = true }) {
                            Icon(Icons.Rounded.QrCodeScanner, contentDescription = "Scan UPI QR", tint = SmartColors.NeonLime)
                        }
                        IconButton(onClick = { viewModel.refreshSplitWorkspace() }) {
                            Icon(Icons.Rounded.Refresh, contentDescription = "Sync", tint = SmartColors.TextPrimary)
                        }
                    }
                }
            }

            item {
                // My App ID + Verified UPI ID card
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(SmartColors.Surface, RoundedCornerShape(18.dp))
                        .border(1.dp, SmartColors.Border, RoundedCornerShape(18.dp))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("MY SMART USER ID", style = MaterialTheme.typography.labelMedium)
                            Text(
                                text = myAppCode,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = SmartColors.NeonLime,
                                fontSize = 18.sp
                            )
                        }
                        OutlinedButton(onClick = { copyToClipboard(context, "Smart User ID", myAppCode) }) {
                            Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Copy ID")
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = upiInput,
                            onValueChange = { upiInput = it },
                            label = { Text("Receiver UPI ID (handle@bank)") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = { viewModel.updateMyUpiId(upiInput) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = SmartColors.NeonLime,
                                contentColor = SmartColors.Background
                            )
                        ) {
                            Text(if (profile.upiVerified) "Update" else "Verify", fontWeight = FontWeight.Bold)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(
                            Icons.Rounded.VerifiedUser,
                            contentDescription = null,
                            tint = if (profile.upiVerified) SmartColors.IncomeMint else SmartColors.TextMuted,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = if (profile.upiVerified) {
                                "Verified format (${profile.upiId}) — No PIN/OTP ever stored."
                            } else {
                                "Add your UPI ID to receive settlements & generate UPI QR codes."
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = if (profile.upiVerified) SmartColors.IncomeMint else SmartColors.TextMuted
                        )
                    }
                }
            }

            item {
                // Dashboard KPI Grid
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SplitMetricCard(
                            label = "TOTAL GROUP SPEND",
                            value = formatMoney(currency, summary.totalGroupExpenses),
                            accent = SmartColors.TextPrimary,
                            modifier = Modifier.weight(1f)
                        )
                        SplitMetricCard(
                            label = "MY OUTSTANDING",
                            value = formatMoney(currency, summary.myOutstandingBalance),
                            accent = SmartColors.ExpenseOrange,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SplitMetricCard(
                            label = "OTHERS OWE ME",
                            value = formatMoney(currency, summary.amountOthersOweMe),
                            accent = SmartColors.NeonLime,
                            modifier = Modifier.weight(1f)
                        )
                        SplitMetricCard(
                            label = "PENDING REQUESTS",
                            value = "${summary.pendingSettlementsCount} active",
                            accent = SmartColors.IncomeMint,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = { showCreateGroupDialog = true },
                        modifier = Modifier.weight(1f).height(48.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SmartColors.NeonLime,
                            contentColor = SmartColors.Background
                        )
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Create Group", fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                    OutlinedButton(
                        onClick = { showJoinGroupDialog = true },
                        modifier = Modifier.weight(1f).height(48.dp)
                    ) {
                        Icon(Icons.Rounded.GroupAdd, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Join Group", maxLines = 1)
                    }
                }
            }

            if (pendingIncomingInvitations.isNotEmpty()) {
                item {
                    Text("PENDING INVITATIONS (${pendingIncomingInvitations.size})", style = MaterialTheme.typography.labelMedium)
                }
                items(pendingIncomingInvitations, key = { it.id }) { inv ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(SmartColors.SurfaceElevated, RoundedCornerShape(16.dp))
                            .border(1.dp, SmartColors.NeonLime.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(inv.groupName, style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = "${inv.groupType.displayName} • Invited by ${inv.inviterName}",
                            color = SmartColors.TextMuted,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { viewModel.respondToGroupInvitation(inv, accept = true) },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = SmartColors.NeonLime,
                                    contentColor = SmartColors.Background
                                )
                            ) { Text("Accept", fontWeight = FontWeight.Bold) }
                            OutlinedButton(
                                onClick = { viewModel.respondToGroupInvitation(inv, accept = false) },
                                modifier = Modifier.weight(1f)
                            ) { Text("Reject") }
                        }
                    }
                }
            }

            item {
                Text("MY GROUPS (${groups.size})", style = MaterialTheme.typography.labelMedium)
            }

            if (groups.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(SmartColors.Surface, RoundedCornerShape(18.dp))
                            .border(1.dp, SmartColors.Border, RoundedCornerShape(18.dp))
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Rounded.Groups, contentDescription = null, tint = SmartColors.NeonLime, modifier = Modifier.size(34.dp))
                        Text("No split groups yet", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Create a Roommates, Friends, Family, or Trip group or join with a group code.",
                            color = SmartColors.TextMuted
                        )
                    }
                }
            } else {
                items(groups, key = { it.id }) { grp ->
                    val gMembers = members.filter { it.groupId == grp.id && it.status == GroupMemberStatus.ACTIVE }
                    val gExpenses = groupExpenses.filter { it.groupId == grp.id }
                    val gSettlements = settlements.filter { it.groupId == grp.id }
                    val balSummary = SplitExpenseCalculator().computeBalances(gMembers, gExpenses, gSettlements)
                    val myNet = balSummary.memberBalances.firstOrNull { it.uid == profile.uid }?.netBalance ?: 0.0

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(SmartColors.Surface, RoundedCornerShape(18.dp))
                            .border(1.dp, SmartColors.Border, RoundedCornerShape(18.dp))
                            .clickable { selectedGroupId = grp.id }
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(grp.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                text = "${grp.groupType.displayName} • ${gMembers.size} members • Code ${grp.joinCode}",
                                style = MaterialTheme.typography.labelMedium
                            )
                            Text(
                                text = "Total: ${formatMoney(currency, balSummary.totalGroupExpenses)}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = SmartColors.TextMuted
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = when {
                                    myNet > 0.01 -> "+${formatMoney(currency, myNet)}"
                                    myNet < -0.01 -> "-${formatMoney(currency, -myNet)}"
                                    else -> "Settled"
                                },
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = when {
                                    myNet > 0.01 -> SmartColors.IncomeMint
                                    myNet < -0.01 -> SmartColors.ExpenseOrange
                                    else -> SmartColors.TextMuted
                                }
                            )
                            Text("Tap to open", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }

            item {
                Spacer(Modifier.height(4.dp))
                Text("SETTLEMENT HISTORY & REQUESTS", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = dueFilter == null,
                        onClick = { dueFilter = null },
                        label = { Text("All") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = SmartColors.NeonLime,
                            selectedLabelColor = SmartColors.Background
                        )
                    )
                    SettlementDueCategory.entries.forEach { cat ->
                        FilterChip(
                            selected = dueFilter == cat,
                            onClick = { dueFilter = cat },
                            label = { Text(cat.name.lowercase().replaceFirstChar { it.uppercase() }) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = SmartColors.NeonLime,
                                selectedLabelColor = SmartColors.Background
                            )
                        )
                    }
                }
            }

            val calc = SplitExpenseCalculator()
            val visibleSettlements = settlements.filter { st ->
                dueFilter == null || calc.categorizeSettlementDue(st, todayDate) == dueFilter
            }
            if (visibleSettlements.isEmpty()) {
                item {
                    Text("No settlement requests match this filter.", color = SmartColors.TextMuted)
                    Spacer(Modifier.height(24.dp))
                }
            } else {
                items(visibleSettlements, key = { it.id }) { st ->
                    SettlementRowCard(
                        settlement = st,
                        dueCategory = calc.categorizeSettlementDue(st, todayDate),
                        currency = currency,
                        currentUid = profile.uid,
                        onOpenWorkflow = { activeSettlementId = st.id }
                    )
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    } else {
        // Group Detail View
        val gMembers = remember(members, selectedGroup.id) {
            members.filter { it.groupId == selectedGroup.id && it.status == GroupMemberStatus.ACTIVE }
        }
        val gExpenses = remember(groupExpenses, selectedGroup.id) {
            groupExpenses.filter { it.groupId == selectedGroup.id && !it.deleted }
        }
        val gSettlements = remember(settlements, selectedGroup.id) {
            settlements.filter { it.groupId == selectedGroup.id }
        }
        val gSummary = remember(gMembers, gExpenses, gSettlements) {
            SplitExpenseCalculator().computeBalances(gMembers, gExpenses, gSettlements)
        }
        val calc = remember { SplitExpenseCalculator() }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(SmartColors.Background)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        IconButton(onClick = { selectedGroupId = null }) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = SmartColors.TextPrimary)
                        }
                        Column {
                            Text(selectedGroup.name, style = MaterialTheme.typography.titleLarge)
                            Text(
                                "${selectedGroup.groupType.displayName} • Admin: ${selectedGroup.creatorName} • Code: ${selectedGroup.joinCode}",
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                    TextButton(onClick = {
                        viewModel.leaveSplitGroup(selectedGroup)
                        selectedGroupId = null
                    }) {
                        Text("Leave", color = SmartColors.DangerRed)
                    }
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = {
                            editingExpense = null
                            showExpenseDialog = true
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SmartColors.NeonLime,
                            contentColor = SmartColors.Background
                        )
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("Add Expense", fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                    OutlinedButton(
                        onClick = { showInviteDialog = true },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Rounded.Share, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("Invite", maxLines = 1)
                    }
                }
            }

            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(SmartColors.Surface, RoundedCornerShape(18.dp))
                        .border(1.dp, SmartColors.Border, RoundedCornerShape(18.dp))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("MEMBER CONTRIBUTIONS", style = MaterialTheme.typography.labelMedium)
                        Text(
                            "Total ${formatMoney(currency, gSummary.totalGroupExpenses)}",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = SmartColors.NeonLime
                        )
                    }
                    gSummary.memberBalances.forEach { mb ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "${mb.displayName}${if (mb.uid == profile.uid) " (You)" else ""}",
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "Paid ${formatMoney(currency, mb.totalPaid)} • Share ${formatMoney(currency, mb.totalFairShare)}",
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                            Text(
                                text = when {
                                    mb.netBalance > 0.01 -> "Gets +${formatMoney(currency, mb.netBalance)}"
                                    mb.netBalance < -0.01 -> "Owes ${formatMoney(currency, -mb.netBalance)}"
                                    else -> "Settled"
                                },
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = when {
                                    mb.netBalance > 0.01 -> SmartColors.IncomeMint
                                    mb.netBalance < -0.01 -> SmartColors.ExpenseOrange
                                    else -> SmartColors.TextMuted
                                }
                            )
                        }
                    }
                }
            }

            item {
                Text("SUGGESTED SETTLEMENTS (MINIMIZED)", style = MaterialTheme.typography.labelMedium)
            }

            if (gSummary.simplifiedDebts.isEmpty()) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(SmartColors.Surface, RoundedCornerShape(14.dp))
                            .border(1.dp, SmartColors.IncomeMint.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = SmartColors.IncomeMint)
                        Text("All balances in ${selectedGroup.name} are settled!", color = SmartColors.IncomeMint)
                    }
                }
            } else {
                items(gSummary.simplifiedDebts, key = { "${it.fromUid}_${it.toUid}" }) { debt ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(SmartColors.SurfaceElevated, RoundedCornerShape(16.dp))
                            .border(1.dp, SmartColors.Border, RoundedCornerShape(16.dp))
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("${debt.fromName} -> ${debt.toName}", fontWeight = FontWeight.SemiBold)
                            Text(
                                text = if (debt.toUpiId.isNotBlank()) "UPI: ${debt.toUpiId}" else "Receiver has not saved UPI ID yet",
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = formatMoney(currency, debt.amount),
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = SmartColors.ExpenseOrange
                            )
                            Button(
                                onClick = {
                                    viewModel.createSettlementFromDebt(
                                        group = selectedGroup,
                                        debt = debt,
                                        dueDate = selectedGroup.settlementDueDate,
                                        method = SettlementPaymentMethod.UPI
                                    ) { created ->
                                        activeSettlementId = created.id
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = SmartColors.NeonLime,
                                    contentColor = SmartColors.Background
                                )
                            ) {
                                Text("SETTLE NOW", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }

            if (gSettlements.isNotEmpty()) {
                item {
                    Text("GROUP SETTLEMENT REQUESTS (${gSettlements.size})", style = MaterialTheme.typography.labelMedium)
                }
                items(gSettlements, key = { it.id }) { st ->
                    SettlementRowCard(
                        settlement = st,
                        dueCategory = calc.categorizeSettlementDue(st, todayDate),
                        currency = currency,
                        currentUid = profile.uid,
                        onOpenWorkflow = { activeSettlementId = st.id }
                    )
                }
            }

            item {
                Text("SHARED EXPENSE LEDGER (${gExpenses.size})", style = MaterialTheme.typography.labelMedium)
            }

            if (gExpenses.isEmpty()) {
                item {
                    Text("No group expenses yet. Tap Add Expense to split a bill.", color = SmartColors.TextMuted)
                    Spacer(Modifier.height(24.dp))
                }
            } else {
                items(gExpenses, key = { it.id }) { exp ->
                    val catText = if (exp.category == ExpenseCategory.Other && exp.customCategoryDetail.isNotBlank()) {
                        "Other — ${exp.customCategoryDetail}"
                    } else {
                        exp.category.displayName
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(SmartColors.Surface, RoundedCornerShape(16.dp))
                            .border(1.dp, SmartColors.Border, RoundedCornerShape(16.dp))
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(exp.description, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            Text(
                                text = formatMoney(currency, exp.amount),
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = SmartColors.ExpenseOrange
                            )
                        }
                        Text(
                            text = "$catText • Paid by ${exp.paidByName} • ${exp.splitType.label}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = SmartColors.TextMuted
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${exp.transactionDate} ${exp.transactionTime} • ${exp.participantUids.size} members",
                                style = MaterialTheme.typography.labelMedium
                            )
                            Row {
                                IconButton(onClick = {
                                    editingExpense = exp
                                    showExpenseDialog = true
                                }) {
                                    Icon(Icons.Rounded.Edit, contentDescription = "Edit", tint = SmartColors.NeonLime, modifier = Modifier.size(18.dp))
                                }
                                IconButton(onClick = { viewModel.deleteGroupExpense(exp) }) {
                                    Icon(Icons.Rounded.Delete, contentDescription = "Delete", tint = SmartColors.DangerRed, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }

    if (showCreateGroupDialog) {
        CreateGroupDialog(
            onDismiss = { showCreateGroupDialog = false },
            onCreate = { name, type, dueDate ->
                viewModel.createSplitGroup(name, type, dueDate)
                showCreateGroupDialog = false
            }
        )
    }

    if (showJoinGroupDialog) {
        JoinGroupDialog(
            onDismiss = { showJoinGroupDialog = false },
            onJoin = { code ->
                viewModel.joinGroupByCode(code)
                showJoinGroupDialog = false
            }
        )
    }

    if (showInviteDialog && selectedGroup != null) {
        InviteMemberDialog(
            group = selectedGroup,
            inviterName = profile.displayName,
            onDismiss = { showInviteDialog = false },
            onSendInApp = { method, target ->
                viewModel.sendGroupInvitation(selectedGroup, method, target)
            }
        )
    }

    if (showExpenseDialog && selectedGroup != null) {
        val gMembers = members.filter { it.groupId == selectedGroup.id && it.status == GroupMemberStatus.ACTIVE }
        AddEditGroupExpenseDialog(
            group = selectedGroup,
            members = gMembers,
            currentUid = profile.uid,
            existing = editingExpense,
            receiptDraft = receiptDraft,
            onScanReceipt = { uri -> viewModel.scanReceiptUri(context, uri) },
            onClearDraft = { viewModel.clearReceiptDraft() },
            onDismiss = {
                editingExpense = null
                showExpenseDialog = false
            },
            onSave = { paidByUid, paidByName, amount, desc, cat, customDetail, date, time, splitType, participantUids, customMap, receiptRef, note ->
                val ok = viewModel.saveGroupExpense(
                    existingId = editingExpense?.id,
                    group = selectedGroup,
                    paidByUid = paidByUid,
                    paidByName = paidByName,
                    amount = amount,
                    description = desc,
                    category = cat,
                    customCategoryDetail = customDetail,
                    dateStr = date,
                    timeStr = time,
                    splitType = splitType,
                    participantUids = participantUids,
                    customInputs = customMap,
                    receiptReference = receiptRef,
                    note = note
                )
                if (ok) {
                    editingExpense = null
                    showExpenseDialog = false
                }
            }
        )
    }

    if (activeSettlement != null) {
        SettlementWorkflowDialog(
            settlement = activeSettlement,
            currentUid = profile.uid,
            currency = currency,
            upiUri = viewModel.buildSettlementUpiUri(activeSettlement),
            viewModel = viewModel,
            onOpenScanner = { showQrScannerDialog = true },
            onDismiss = { activeSettlementId = null }
        )
    }

    if (showQrScannerDialog) {
        UpiQrScannerDialog(
            viewModel = viewModel,
            onDismiss = { showQrScannerDialog = false }
        )
    }
}

@Composable
private fun SplitMetricCard(
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(SmartColors.Surface, RoundedCornerShape(16.dp))
            .border(1.dp, SmartColors.Border, RoundedCornerShape(16.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            text = value,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
            color = accent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SettlementRowCard(
    settlement: Settlement,
    dueCategory: SettlementDueCategory,
    currency: String,
    currentUid: String,
    onOpenWorkflow: () -> Unit
) {
    val dueColor = when (dueCategory) {
        SettlementDueCategory.COMPLETED -> SmartColors.IncomeMint
        SettlementDueCategory.OVERDUE -> SmartColors.DangerRed
        SettlementDueCategory.DUE -> SmartColors.ExpenseOrange
        SettlementDueCategory.UPCOMING -> SmartColors.NeonLime
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SmartColors.Surface, RoundedCornerShape(16.dp))
            .border(1.dp, SmartColors.Border, RoundedCornerShape(16.dp))
            .clickable { onOpenWorkflow() }
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${settlement.payerName} -> ${settlement.receiverName}", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(
                text = formatMoney(currency, settlement.amount),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = dueColor
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = "${settlement.groupName} • ${settlement.status.label}",
                style = MaterialTheme.typography.bodyMedium,
                color = SmartColors.TextMuted
            )
            Text(
                text = dueCategory.name,
                style = MaterialTheme.typography.labelMedium,
                color = dueColor,
                fontWeight = FontWeight.Bold
            )
        }
        if (settlement.dueDate.isNotBlank() || settlement.paymentReference.isNotBlank()) {
            Text(
                text = buildString {
                    if (settlement.dueDate.isNotBlank()) append("Due: ${settlement.dueDate}")
                    if (settlement.paymentReference.isNotBlank()) {
                        if (isNotEmpty()) append(" • ")
                        append("Ref: ${settlement.paymentReference}")
                    }
                },
                style = MaterialTheme.typography.labelMedium
            )
        }
        Text(
            text = when {
                settlement.status == SettlementStatus.COMPLETED -> "Verified by both parties"
                settlement.receiverUid == currentUid && settlement.status == SettlementStatus.AWAITING_CONFIRMATION ->
                    "Tap to Confirm Receipt"
                settlement.payerUid == currentUid -> "Tap to Pay via UPI / QR / Submit Ref"
                else -> "Tap to view verification status"
            },
            style = MaterialTheme.typography.labelMedium,
            color = SmartColors.NeonLime
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CreateGroupDialog(
    onDismiss: () -> Unit,
    onCreate: (String, GroupType, String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var groupType by remember { mutableStateOf(GroupType.ROOMMATES) }
    var dueDate by remember { mutableStateOf(LocalDate.now().plusDays(7).toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SmartColors.Surface,
        title = { Text("Create Split Group") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Group Name (e.g. Flat 402)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text("Group Type", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    GroupType.entries.forEach { t ->
                        FilterChip(
                            selected = groupType == t,
                            onClick = { groupType = t },
                            label = { Text(t.displayName) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = SmartColors.NeonLime,
                                selectedLabelColor = SmartColors.Background
                            )
                        )
                    }
                }
                OutlinedTextField(
                    value = dueDate,
                    onValueChange = { dueDate = it },
                    label = { Text("Default Due Date (YYYY-MM-DD)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onCreate(name, groupType, dueDate) },
                colors = ButtonDefaults.buttonColors(containerColor = SmartColors.NeonLime, contentColor = SmartColors.Background)
            ) { Text("Create", fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun JoinGroupDialog(
    onDismiss: () -> Unit,
    onJoin: (String) -> Unit
) {
    var code by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SmartColors.Surface,
        title = { Text("Join Group by Code") },
        text = {
            OutlinedTextField(
                value = code,
                onValueChange = { code = it.uppercase() },
                label = { Text("Group Join Code (e.g. GRP-XXXXXX)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(
                onClick = { onJoin(code) },
                colors = ButtonDefaults.buttonColors(containerColor = SmartColors.NeonLime, contentColor = SmartColors.Background)
            ) { Text("Join Group", fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InviteMemberDialog(
    group: SplitGroup,
    inviterName: String,
    onDismiss: () -> Unit,
    onSendInApp: (InviteMethod, String) -> Unit
) {
    val context = LocalContext.current
    var method by remember { mutableStateOf(InviteMethod.APP_ID) }
    var targetInput by remember { mutableStateOf("") }
    var permissionConfirmed by remember { mutableStateOf(false) }

    val shareText = "Hi! $inviterName invited you to '${group.name}' (${group.groupType.displayName}) on Smart Expense Manager. Join using code: ${group.joinCode}"

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SmartColors.Surface,
        title = { Text("Invite to ${group.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text("Invitation Method", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    InviteMethod.entries.forEach { m ->
                        FilterChip(
                            selected = method == m,
                            onClick = { method = m },
                            label = { Text(m.name.replace("_", " ")) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = SmartColors.NeonLime,
                                selectedLabelColor = SmartColors.Background
                            )
                        )
                    }
                }
                when (method) {
                    InviteMethod.APP_ID -> OutlinedTextField(
                        value = targetInput,
                        onValueChange = { targetInput = it.uppercase() },
                        label = { Text("Friend Smart User ID (SEM-...)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    InviteMethod.EMAIL -> OutlinedTextField(
                        value = targetInput,
                        onValueChange = { targetInput = it },
                        label = { Text("Registered Email Address") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    InviteMethod.SMS -> OutlinedTextField(
                        value = targetInput,
                        onValueChange = { targetInput = it },
                        label = { Text("Phone Number for SMS") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    InviteMethod.WHATSAPP -> Text(
                        "Uses Android's official share mechanism so you review and tap Send in WhatsApp.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = SmartColors.TextMuted
                    )
                    InviteMethod.IN_APP -> Text(
                        "Creates an in-app notification & shareable group join code: ${group.joinCode}.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = SmartColors.TextMuted
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = permissionConfirmed,
                        onCheckedChange = { permissionConfirmed = it },
                        colors = CheckboxDefaults.colors(checkedColor = SmartColors.NeonLime)
                    )
                    Text(
                        "I confirm permission to send this invitation. Messages are never auto-sent without confirmation.",
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = permissionConfirmed,
                onClick = {
                    onSendInApp(method, targetInput)
                    if (method == InviteMethod.WHATSAPP) {
                        val waIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, shareText)
                            setPackage("com.whatsapp")
                        }
                        runCatching { context.startActivity(waIntent) }.onFailure {
                            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, shareText)
                            }, "Share Group Invitation"))
                        }
                    } else if (method == InviteMethod.SMS) {
                        val cleanPhone = targetInput.filter { it.isDigit() || it == '+' }
                        val smsIntent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$cleanPhone")).apply {
                            putExtra("sms_body", shareText)
                        }
                        runCatching { context.startActivity(smsIntent) }
                    }
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = SmartColors.NeonLime, contentColor = SmartColors.Background)
            ) {
                Text("Send Invite", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddEditGroupExpenseDialog(
    group: SplitGroup,
    members: List<GroupMember>,
    currentUid: String,
    existing: GroupExpense?,
    receiptDraft: com.smartexpense.manager.domain.model.ExtractedReceiptDraft?,
    onScanReceipt: (Uri) -> Unit,
    onClearDraft: () -> Unit,
    onDismiss: () -> Unit,
    onSave: (
        paidByUid: String,
        paidByName: String,
        amount: Double,
        desc: String,
        category: ExpenseCategory,
        customDetail: String,
        date: String,
        time: String,
        splitType: SplitType,
        participantUids: List<String>,
        customMap: Map<String, Double>,
        receiptRef: String,
        note: String
    ) -> Unit
) {
    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let(onScanReceipt)
    }

    var paidByUid by remember(existing) {
        mutableStateOf(existing?.paidByUid ?: currentUid)
    }
    var amountStr by remember(existing) {
        mutableStateOf(existing?.amount?.toString() ?: "")
    }
    var desc by remember(existing) {
        mutableStateOf(existing?.description ?: "")
    }
    var category by remember(existing) {
        mutableStateOf(existing?.category ?: ExpenseCategory.Food)
    }
    var customDetail by remember(existing) {
        mutableStateOf(existing?.customCategoryDetail ?: "")
    }
    var dateStr by remember(existing) {
        mutableStateOf(existing?.transactionDate ?: LocalDate.now().toString())
    }
    var timeStr by remember(existing) {
        mutableStateOf(
            existing?.transactionTime ?: "%02d:%02d".format(LocalTime.now().hour, LocalTime.now().minute)
        )
    }
    var splitType by remember(existing) {
        mutableStateOf(existing?.splitType ?: SplitType.EQUAL)
    }
    var receiptRef by remember(existing) {
        mutableStateOf(existing?.receiptReference ?: "")
    }
    var note by remember(existing) {
        mutableStateOf(existing?.note ?: "")
    }
    val selectedParticipants = remember(existing, members) {
        mutableStateMapOf<String, Boolean>().apply {
            val base = existing?.participantUids ?: members.map { it.uid }
            members.forEach { put(it.uid, it.uid in base) }
        }
    }
    val customInputs = remember(existing, members) {
        mutableStateMapOf<String, String>().apply {
            members.forEach { m ->
                val v = existing?.shares?.get(m.uid)
                put(m.uid, if (v != null && existing.splitType == SplitType.CUSTOM_AMOUNT) v.toString() else "1")
            }
        }
    }

    LaunchedEffect(receiptDraft) {
        receiptDraft?.let { d ->
            if (d.amount.isNotBlank()) amountStr = d.amount
            if (d.merchantName.isNotBlank()) desc = d.merchantName
            if (d.transactionDate.isNotBlank()) dateStr = d.transactionDate
            if (d.transactionTime.isNotBlank()) timeStr = d.transactionTime
            if (d.referenceId.isNotBlank()) receiptRef = d.referenceId
        }
    }

    Dialog(onDismissRequest = {
        onClearDraft()
        onDismiss()
    }) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(SmartColors.Surface, RoundedCornerShape(20.dp))
                .border(1.dp, SmartColors.Border, RoundedCornerShape(20.dp))
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(if (existing != null) "Edit Group Expense" else "Add Group Expense", style = MaterialTheme.typography.titleLarge)
                OutlinedButton(onClick = { galleryLauncher.launch("image/*") }) {
                    Icon(Icons.Rounded.ReceiptLong, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Receipt", fontSize = 12.sp)
                }
            }

            OutlinedTextField(
                value = amountStr,
                onValueChange = { amountStr = it },
                label = { Text("Amount") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = desc,
                onValueChange = { desc = it },
                label = { Text("Description") },
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = dateStr, onValueChange = { dateStr = it }, label = { Text("Date") }, modifier = Modifier.weight(1f))
                OutlinedTextField(value = timeStr, onValueChange = { timeStr = it }, label = { Text("Time") }, modifier = Modifier.weight(1f))
            }

            Text("Paid By", style = MaterialTheme.typography.labelMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                members.forEach { m ->
                    FilterChip(
                        selected = paidByUid == m.uid,
                        onClick = { paidByUid = m.uid },
                        label = { Text(m.displayName) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = SmartColors.NeonLime,
                            selectedLabelColor = SmartColors.Background
                        )
                    )
                }
            }

            Text("Category", style = MaterialTheme.typography.labelMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ExpenseCategory.expenseCategories.forEach { cat ->
                    FilterChip(
                        selected = category == cat,
                        onClick = {
                            if (cat != ExpenseCategory.Other) customDetail = ""
                            category = cat
                        },
                        label = { Text(cat.displayName) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = SmartColors.NeonLime,
                            selectedLabelColor = SmartColors.Background
                        )
                    )
                }
            }

            AnimatedVisibility(visible = category == ExpenseCategory.Other) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(SmartColors.Background, RoundedCornerShape(12.dp))
                        .border(1.dp, SmartColors.NeonLime.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "Where have you spent this money? *",
                        style = MaterialTheme.typography.labelMedium,
                        color = SmartColors.NeonLime,
                        fontWeight = FontWeight.SemiBold
                    )
                    OutlinedTextField(
                        value = customDetail,
                        onValueChange = { customDetail = it.take(120) },
                        placeholder = { Text("Enter expense details (e.g. Mobile recharge, Movie ticket, Gift...)") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = SmartColors.NeonLime,
                            unfocusedBorderColor = SmartColors.NeonLime.copy(alpha = 0.4f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            Text("Split Type", style = MaterialTheme.typography.labelMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SplitType.entries.forEach { st ->
                    FilterChip(
                        selected = splitType == st,
                        onClick = { splitType = st },
                        label = { Text(st.label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = SmartColors.NeonLime,
                            selectedLabelColor = SmartColors.Background
                        )
                    )
                }
            }

            Text("Split Members", style = MaterialTheme.typography.labelMedium)
            members.forEach { m ->
                val checked = selectedParticipants[m.uid] == true
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = { selectedParticipants[m.uid] = it },
                            colors = CheckboxDefaults.colors(checkedColor = SmartColors.NeonLime)
                        )
                        Text(m.displayName)
                    }
                    if (checked && splitType != SplitType.EQUAL) {
                        OutlinedTextField(
                            value = customInputs[m.uid].orEmpty(),
                            onValueChange = { customInputs[m.uid] = it },
                            label = {
                                Text(
                                    when (splitType) {
                                        SplitType.CUSTOM_AMOUNT -> "Amount"
                                        SplitType.PERCENTAGE -> "%"
                                        SplitType.SHARES -> "Shares"
                                        else -> ""
                                    }
                                )
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier.width(105.dp)
                        )
                    }
                }
            }

            OutlinedTextField(value = receiptRef, onValueChange = { receiptRef = it }, label = { Text("Receipt Ref / Invoice (optional)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text("Note (optional)") }, modifier = Modifier.fillMaxWidth())

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = {
                        onClearDraft()
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Cancel") }
                Button(
                    onClick = {
                        val amt = amountStr.toDoubleOrNull() ?: 0.0
                        val participants = members.filter { selectedParticipants[it.uid] == true }.map { it.uid }
                        val payerName = members.firstOrNull { it.uid == paidByUid }?.displayName ?: group.creatorName
                        val map = participants.associateWith { uid ->
                            customInputs[uid]?.toDoubleOrNull() ?: 0.0
                        }
                        onSave(
                            paidByUid,
                            payerName,
                            amt,
                            desc,
                            category,
                            customDetail,
                            dateStr,
                            timeStr,
                            splitType,
                            participants,
                            map,
                            receiptRef,
                            note
                        )
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = SmartColors.NeonLime, contentColor = SmartColors.Background)
                ) { Text("Save Split", fontWeight = FontWeight.Bold) }
            }
        }
    }
}

@Composable
private fun SettlementWorkflowDialog(
    settlement: Settlement,
    currentUid: String,
    currency: String,
    upiUri: String,
    viewModel: MainViewModel,
    onOpenScanner: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var paymentRefInput by remember(settlement.id) { mutableStateOf(settlement.paymentReference) }
    var recordPersonal by remember { mutableStateOf(true) }
    var showQr by remember { mutableStateOf(false) }
    val qrBitmap = remember(upiUri, showQr) {
        if (showQr && upiUri.isNotBlank()) viewModel.generateQrBitmap(upiUri) else null
    }
    val isPayer = settlement.payerUid == currentUid
    val isReceiver = settlement.receiverUid == currentUid

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(SmartColors.Surface, RoundedCornerShape(20.dp))
                .border(1.dp, SmartColors.Border, RoundedCornerShape(20.dp))
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Settlement Verification", style = MaterialTheme.typography.titleLarge)
            Text(
                text = "${settlement.payerName} -> ${settlement.receiverName} • ${formatMoney(currency, settlement.amount)}",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = SmartColors.NeonLime
            )
            Text("Status: ${settlement.status.label}", style = MaterialTheme.typography.bodyMedium)
            if (settlement.receiverUpiId.isNotBlank()) {
                Text("Receiver UPI: ${settlement.receiverUpiId}", style = MaterialTheme.typography.labelMedium)
            }

            val compatibleUpiApps by viewModel.compatibleUpiApps.collectAsStateWithLifecycle()
            LaunchedEffect(settlement.id) {
                viewModel.refreshCompatibleUpiApps()
            }

            if (isPayer && settlement.status != SettlementStatus.COMPLETED) {
                if (upiUri.isNotBlank()) {
                    Text(
                        text = "Pay via UPI (installed apps on this device):",
                        style = MaterialTheme.typography.labelMedium,
                        color = SmartColors.TextMuted
                    )
                    if (compatibleUpiApps.isNotEmpty()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            compatibleUpiApps.take(3).forEach { app ->
                                OutlinedButton(
                                    onClick = {
                                        viewModel.performSettlementAction(
                                            settlement = settlement,
                                            action = SettlementAction.INITIATE_UPI,
                                            paymentApp = app.appName,
                                            method = SettlementPaymentMethod.UPI
                                        )
                                        val intent = viewModel.buildTargetedUpiPaymentIntent(upiUri, app.packageName)
                                        runCatching { context.startActivity(intent) }
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(app.appName, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp)
                                }
                            }
                        }
                    } else {
                        Text(
                            text = "No dedicated UPI apps detected; tap Pay via UPI App to open system chooser or scan QR.",
                            style = MaterialTheme.typography.labelMedium,
                            color = SmartColors.ExpenseOrange
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        enabled = upiUri.isNotBlank(),
                        onClick = {
                            viewModel.performSettlementAction(
                                settlement = settlement,
                                action = SettlementAction.INITIATE_UPI,
                                paymentApp = "UPI Intent",
                                method = SettlementPaymentMethod.UPI
                            )
                            val intent = viewModel.buildTargetedUpiPaymentIntent(upiUri, null)
                            runCatching {
                                context.startActivity(Intent.createChooser(intent, "Pay with UPI App"))
                            }
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = SmartColors.NeonLime, contentColor = SmartColors.Background)
                    ) { Text("Pay via UPI App", fontWeight = FontWeight.Bold, maxLines = 1) }

                    OutlinedButton(
                        enabled = upiUri.isNotBlank(),
                        onClick = { showQr = !showQr },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Rounded.QrCode2, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (showQr) "Hide QR" else "Show QR", maxLines = 1)
                    }
                }

                OutlinedButton(
                    onClick = onOpenScanner,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Rounded.QrCodeScanner, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Scan Friend's UPI QR Code")
                }

                if (qrBitmap != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.White, RoundedCornerShape(14.dp))
                            .padding(12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(bitmap = qrBitmap, contentDescription = "UPI QR Code", modifier = Modifier.size(200.dp))
                    }
                }

                OutlinedTextField(
                    value = paymentRefInput,
                    onValueChange = { paymentRefInput = it },
                    label = { Text("UTR / UPI Reference ID (after paying)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = {
                        viewModel.performSettlementAction(
                            settlement = settlement,
                            action = SettlementAction.SUBMIT_PAYER_PROOF,
                            paymentApp = settlement.paymentApp.ifBlank { "UPI" },
                            paymentReference = paymentRefInput,
                            method = SettlementPaymentMethod.UPI
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = SmartColors.IncomeMint, contentColor = SmartColors.Background)
                ) { Text("Mark as Paid (Submit Reference)", fontWeight = FontWeight.Bold) }
            }

            if (isReceiver && settlement.status != SettlementStatus.COMPLETED) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = recordPersonal,
                        onCheckedChange = { recordPersonal = it },
                        colors = CheckboxDefaults.colors(checkedColor = SmartColors.NeonLime)
                    )
                    Text("Also record received payment in personal ledger", style = MaterialTheme.typography.labelMedium)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = {
                            viewModel.performSettlementAction(
                                settlement = settlement,
                                action = SettlementAction.RECEIVER_CONFIRM,
                                paymentReference = paymentRefInput,
                                recordInPersonalLedger = recordPersonal
                            )
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = SmartColors.IncomeMint, contentColor = SmartColors.Background)
                    ) { Text("Confirm Receipt", fontWeight = FontWeight.Bold) }

                    OutlinedButton(
                        onClick = {
                            viewModel.performSettlementAction(settlement, SettlementAction.REJECT)
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("Reject", color = SmartColors.DangerRed) }
                }
            }

            if (settlement.status == SettlementStatus.COMPLETED) {
                Text(
                    "Settlement verified by both parties. Ref: ${settlement.paymentReference}",
                    color = SmartColors.IncomeMint,
                    fontWeight = FontWeight.SemiBold
                )
            }

            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("Close") }
        }
    }
}

@Composable
private fun UpiQrScannerDialog(
    viewModel: MainViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasCameraPermission by remember { mutableStateOf(false) }
    var parsedQr by remember { mutableStateOf<ParsedUpiUri?>(null) }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasCameraPermission = granted
    }

    LaunchedEffect(Unit) {
        permLauncher.launch(Manifest.permission.CAMERA)
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(SmartColors.Surface, RoundedCornerShape(20.dp))
                .border(1.dp, SmartColors.Border, RoundedCornerShape(20.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Scan UPI Payment QR", style = MaterialTheme.typography.titleLarge)

            if (hasCameraPermission && parsedQr == null) {
                val previewView = remember { PreviewView(context) }
                val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
                DisposableEffect(Unit) {
                    val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
                    val scanner = BarcodeScanning.getClient()
                    cameraProviderFuture.addListener({
                        val provider = cameraProviderFuture.get()
                        val preview = Preview.Builder().build().also {
                            it.surfaceProvider = previewView.surfaceProvider
                        }
                        val analysis = ImageAnalysis.Builder()
                            .setTargetResolution(Size(1280, 720))
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                            .also { imageAnalysis ->
                                imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                                    val mediaImage = imageProxy.image
                                    if (mediaImage != null) {
                                        val input = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                                        scanner.process(input)
                                            .addOnSuccessListener { barcodes ->
                                                val raw = barcodes.firstNotNullOfOrNull { it.rawValue }
                                                if (!raw.isNullOrBlank()) {
                                                    val parsed = viewModel.parseScannedQrUri(raw)
                                                    parsedQr = parsed
                                                }
                                            }
                                            .addOnCompleteListener { imageProxy.close() }
                                    } else {
                                        imageProxy.close()
                                    }
                                }
                            }
                        runCatching {
                            provider.unbindAll()
                            provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                        }
                    }, ContextCompat.getMainExecutor(context))
                    onDispose {
                        cameraExecutor.shutdown()
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp)
                        .clip(RoundedCornerShape(14.dp))
                ) {
                    AndroidView({ previewView }, modifier = Modifier.fillMaxSize())
                }
            } else if (!hasCameraPermission) {
                Text("Camera permission is required to scan a live UPI QR code.", color = SmartColors.TextMuted)
            }

            parsedQr?.let { parsed ->
                if (!parsed.valid) {
                    Text(parsed.error ?: "Invalid UPI QR", color = SmartColors.DangerRed)
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(SmartColors.SurfaceElevated, RoundedCornerShape(14.dp))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text("Verified UPI Payment QR", color = SmartColors.IncomeMint, fontWeight = FontWeight.Bold)
                        Text("Payee: ${parsed.payeeName.ifBlank { parsed.upiId }}")
                        Text("UPI ID: ${parsed.upiId}", style = MaterialTheme.typography.labelMedium)
                        if (parsed.amount > 0) {
                            Text("Amount: ₹${String.format(Locale.US, "%.2f", parsed.amount)}", fontFamily = FontFamily.Monospace)
                        }
                        Button(
                            onClick = {
                                val uri = SplitExpenseCalculator().buildUpiPaymentUri(
                                    parsed.upiId,
                                    parsed.payeeName.ifBlank { "Friend" },
                                    if (parsed.amount > 0) parsed.amount else 1.0,
                                    parsed.note.ifBlank { "Split settlement" },
                                    parsed.transactionRef.ifBlank { "SEMQR${System.currentTimeMillis().toString().takeLast(6)}" }
                                )
                                runCatching {
                                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW, Uri.parse(uri)), "Open UPI App"))
                                }
                                onDismiss()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = SmartColors.NeonLime, contentColor = SmartColors.Background)
                        ) { Text("Proceed to UPI App", fontWeight = FontWeight.Bold) }
                    }
                }
            }

            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("Close") }
        }
    }
}

private fun formatMoney(currency: String, amount: Double): String =
    "$currency${String.format(Locale.getDefault(), "%,.2f", amount)}"

private fun copyToClipboard(context: Context, label: String, value: String) {
    val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    cb.setPrimaryClip(ClipData.newPlainText(label, value))
}
