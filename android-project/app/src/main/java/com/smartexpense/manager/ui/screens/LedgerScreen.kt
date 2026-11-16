package com.smartexpense.manager.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.smartexpense.manager.domain.model.DateFilterPreset
import com.smartexpense.manager.domain.model.ExpenseCategory
import com.smartexpense.manager.domain.model.FilteredLedgerState
import com.smartexpense.manager.domain.model.Transaction
import com.smartexpense.manager.domain.model.TransactionFilter
import com.smartexpense.manager.domain.model.TransactionType
import com.smartexpense.manager.ui.theme.SmartColors

private val DEFAULT_PAYMENT_METHODS = listOf(
    "UPI",
    "Google Pay (UPI)",
    "PhonePe (UPI)",
    "Paytm (UPI)",
    "Debit Card",
    "Credit Card",
    "Cash",
    "Net Banking"
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LedgerScreen(
    currency: String,
    state: FilteredLedgerState,
    filter: TransactionFilter,
    availablePaymentMethods: List<String>,
    onSearchChange: (String) -> Unit,
    onDatePresetSelect: (DateFilterPreset) -> Unit,
    onCustomDateRange: (String?, String?) -> Unit,
    onToggleCategory: (ExpenseCategory) -> Unit,
    onClearCategories: () -> Unit,
    onTogglePaymentMethod: (String) -> Unit,
    onClearPaymentMethods: () -> Unit,
    onSelectType: (TransactionType?) -> Unit,
    onClearAllFilters: () -> Unit,
    onEditTransaction: (Transaction) -> Unit,
    onDeleteTransaction: (String) -> Unit,
    onOpenPdfExport: () -> Unit
) {
    var filtersExpanded by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<Transaction?>(null) }

    val mergedPaymentMethods = remember(availablePaymentMethods) {
        (DEFAULT_PAYMENT_METHODS + availablePaymentMethods).distinct()
    }

    val newestFirst = remember(state.entries) { state.entries.reversed() }
    val grouped = remember(newestFirst) {
        newestFirst.groupBy { it.transaction.transactionDate }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(SmartColors.Background)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Header Row
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Transaction Ledger", style = MaterialTheme.typography.headlineLarge)
                OutlinedButton(onClick = onOpenPdfExport) {
                    Icon(Icons.Rounded.PictureAsPdf, contentDescription = null, tint = SmartColors.NeonLime)
                    Text(" PDF")
                }
            }
        }

        // Search & Filter Drawer Trigger
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = filter.searchQuery,
                    onValueChange = onSearchChange,
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    trailingIcon = {
                        if (filter.searchQuery.isNotBlank()) {
                            IconButton(onClick = { onSearchChange("") }) {
                                Icon(Icons.Rounded.Close, contentDescription = "Clear search")
                            }
                        }
                    },
                    label = { Text("Description, merchant, note or ref ID") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(
                    onClick = { filtersExpanded = !filtersExpanded },
                    modifier = Modifier.height(56.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Rounded.FilterList, contentDescription = "Filters", tint = SmartColors.NeonLime)
                    Text(
                        text = if (filter.activeCount > 0) " (${filter.activeCount})" else " Filter",
                        color = SmartColors.TextPrimary
                    )
                }
            }
        }

        // Expandable Multi-Criteria Filter Panel
        item {
            AnimatedVisibility(visible = filtersExpanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(SmartColors.Surface, RoundedCornerShape(18.dp))
                        .border(1.dp, SmartColors.Border, RoundedCornerShape(18.dp))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Combine Filters", style = MaterialTheme.typography.titleMedium, color = SmartColors.NeonLime)
                        if (filter.hasActiveFilters) {
                            TextButton(onClick = onClearAllFilters) {
                                Text("Clear All", color = SmartColors.ExpenseOrange)
                            }
                        }
                    }

                    // Transaction Type
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Transaction Type", style = MaterialTheme.typography.labelMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(null to "All", TransactionType.EXPENSE to "Expenses", TransactionType.INCOME to "Income")
                                .forEach { (typeVal, label) ->
                                    FilterChip(
                                        selected = filter.transactionType == typeVal,
                                        onClick = { onSelectType(typeVal) },
                                        label = { Text(label) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = SmartColors.NeonLime,
                                            selectedLabelColor = SmartColors.Background
                                        )
                                    )
                                }
                        }
                    }

                    // Date Range Presets & Custom Inputs
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Date Range", style = MaterialTheme.typography.labelMedium)
                            if (filter.datePreset != DateFilterPreset.ALL) {
                                Text(
                                    text = "Reset",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = SmartColors.NeonLime,
                                    modifier = Modifier.clickable { onDatePresetSelect(DateFilterPreset.ALL) }
                                )
                            }
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            DateFilterPreset.entries.forEach { preset ->
                                FilterChip(
                                    selected = filter.datePreset == preset,
                                    onClick = { onDatePresetSelect(preset) },
                                    label = { Text(preset.label) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = SmartColors.NeonLime,
                                        selectedLabelColor = SmartColors.Background
                                    )
                                )
                            }
                        }
                        if (filter.datePreset == DateFilterPreset.CUSTOM) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = filter.startDate.orEmpty(),
                                    onValueChange = { onCustomDateRange(it, filter.endDate) },
                                    label = { Text("From (YYYY-MM-DD)") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                                OutlinedTextField(
                                    value = filter.endDate.orEmpty(),
                                    onValueChange = { onCustomDateRange(filter.startDate, it) },
                                    label = { Text("To (YYYY-MM-DD)") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }

                    // Multi-Select Categories
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Categories (Multi-select)", style = MaterialTheme.typography.labelMedium)
                            if (filter.selectedCategories.isNotEmpty()) {
                                Text(
                                    text = "Reset",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = SmartColors.NeonLime,
                                    modifier = Modifier.clickable { onClearCategories() }
                                )
                            }
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            ExpenseCategory.entries.forEach { cat ->
                                FilterChip(
                                    selected = cat in filter.selectedCategories,
                                    onClick = { onToggleCategory(cat) },
                                    label = { Text(cat.displayName) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = SmartColors.NeonLime,
                                        selectedLabelColor = SmartColors.Background
                                    )
                                )
                            }
                        }
                    }

                    // Multi-Select Payment Methods
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Payment Methods (Multi-select)", style = MaterialTheme.typography.labelMedium)
                            if (filter.selectedPaymentMethods.isNotEmpty()) {
                                Text(
                                    text = "Reset",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = SmartColors.NeonLime,
                                    modifier = Modifier.clickable { onClearPaymentMethods() }
                                )
                            }
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            mergedPaymentMethods.forEach { method ->
                                FilterChip(
                                    selected = method in filter.selectedPaymentMethods,
                                    onClick = { onTogglePaymentMethod(method) },
                                    label = { Text(method) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = SmartColors.NeonLime,
                                        selectedLabelColor = SmartColors.Background
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }

        // Removable Active Filter Bar
        if (filter.hasActiveFilters) {
            item {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (filter.transactionType != null) {
                        item {
                            FilterChip(
                                selected = true,
                                onClick = { onSelectType(null) },
                                label = { Text("${filter.transactionType.name} ×") }
                            )
                        }
                    }
                    if (filter.datePreset != DateFilterPreset.ALL) {
                        val rangeLabel = if (filter.startDate != null || filter.endDate != null) {
                            "${filter.startDate ?: "..."} → ${filter.endDate ?: "..."}"
                        } else filter.datePreset.label
                        item {
                            FilterChip(
                                selected = true,
                                onClick = { onDatePresetSelect(DateFilterPreset.ALL) },
                                label = { Text("$rangeLabel ×") }
                            )
                        }
                    }
                    items(filter.selectedCategories.toList()) { cat ->
                        FilterChip(
                            selected = true,
                            onClick = { onToggleCategory(cat) },
                            label = { Text("${cat.displayName} ×") }
                        )
                    }
                    items(filter.selectedPaymentMethods.toList()) { method ->
                        FilterChip(
                            selected = true,
                            onClick = { onTogglePaymentMethod(method) },
                            label = { Text("$method ×") }
                        )
                    }
                    item {
                        TextButton(onClick = onClearAllFilters) {
                            Text("Clear All", color = SmartColors.ExpenseOrange)
                        }
                    }
                }
            }
        }

        // Live Filtered Summary Strip
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SmartColors.Surface, RoundedCornerShape(14.dp))
                    .border(1.dp, SmartColors.Border, RoundedCornerShape(14.dp))
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("Matching", style = MaterialTheme.typography.labelMedium)
                    Text("${state.entries.size} / ${state.totalTransactionCount}", fontWeight = FontWeight.Bold)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("Expenses", style = MaterialTheme.typography.labelMedium)
                    Text("-$currency${"%,.2f".format(state.filteredExpenseTotal)}", color = SmartColors.ExpenseOrange, fontWeight = FontWeight.Bold)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("Income", style = MaterialTheme.typography.labelMedium)
                    Text("+$currency${"%,.2f".format(state.filteredIncomeTotal)}", color = SmartColors.IncomeMint, fontWeight = FontWeight.Bold)
                }
            }
        }

        if (grouped.isEmpty()) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(SmartColors.Surface, RoundedCornerShape(16.dp))
                        .border(1.dp, SmartColors.Border, RoundedCornerShape(16.dp))
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = if (filter.hasActiveFilters) {
                            "No transactions match your combined filters."
                        } else {
                            "No transactions recorded yet."
                        },
                        color = SmartColors.TextMuted
                    )
                    if (filter.hasActiveFilters) {
                        Button(
                            onClick = onClearAllFilters,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = SmartColors.NeonLime,
                                contentColor = SmartColors.Background
                            )
                        ) {
                            Text("Reset All Filters", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        } else {
            grouped.forEach { (date, dayEntries) ->
                item(key = "header_$date") {
                    Text(date, style = MaterialTheme.typography.labelMedium, color = SmartColors.NeonLime)
                }
                items(dayEntries, key = { it.transaction.id }) { entry ->
                    val tx = entry.transaction
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(SmartColors.Surface, RoundedCornerShape(14.dp))
                            .border(1.dp, SmartColors.Border, RoundedCornerShape(14.dp))
                            .clickable { onEditTransaction(tx) }
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(tx.description, fontWeight = FontWeight.SemiBold)
                            Text(
                                text = "${tx.categoryLabel()} · ${tx.transactionTime} · ${tx.paymentMethod.ifBlank { tx.sourceOfMoney }}",
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            val sign = if (tx.type == TransactionType.EXPENSE) "-" else "+"
                            val color = if (tx.type == TransactionType.EXPENSE) SmartColors.ExpenseOrange else SmartColors.IncomeMint
                            Text("$sign$currency${"%,.2f".format(tx.amount)}", color = color, fontWeight = FontWeight.Bold)
                            Text("Bal: $currency${"%,.2f".format(entry.runningBalanceAfter)}", style = MaterialTheme.typography.labelMedium)
                        }
                        IconButton(onClick = { deleteTarget = tx }) {
                            Icon(Icons.Rounded.Delete, contentDescription = "Delete", tint = SmartColors.TextMuted)
                        }
                    }
                }
            }
        }
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete Transaction?") },
            text = { Text("Delete '${target.description}' and recalculate running balances?") },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteTransaction(target.id)
                    deleteTarget = null
                }) { Text("Delete", color = SmartColors.DangerRed) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            }
        )
    }
}
