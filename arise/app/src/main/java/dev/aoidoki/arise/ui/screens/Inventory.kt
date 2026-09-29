package dev.aoidoki.arise.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Diamond
import androidx.compose.material.icons.outlined.LocalDrink
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import dev.aoidoki.arise.engine.Item
import dev.aoidoki.arise.ui.UiState
import dev.aoidoki.arise.ui.components.ButtonKind
import dev.aoidoki.arise.ui.components.Chip
import dev.aoidoki.arise.ui.components.Hairline
import dev.aoidoki.arise.ui.components.Pane
import dev.aoidoki.arise.ui.components.SysButton
import dev.aoidoki.arise.ui.components.SystemWindow
import dev.aoidoki.arise.ui.components.Tag
import dev.aoidoki.arise.ui.components.systemShape
import dev.aoidoki.arise.ui.theme.LocalSys
import dev.aoidoki.arise.ui.theme.Palette
import dev.aoidoki.arise.ui.theme.SysType

fun Item.icon(): ImageVector = when (this) {
    Item.HEALING_POTION -> Icons.Outlined.LocalDrink
    Item.MANA_CRYSTAL -> Icons.Outlined.Diamond
    Item.STAMINA_TONIC -> Icons.Outlined.Bolt
    Item.STREAK_WARD -> Icons.Outlined.Shield
    Item.ELIXIR_OF_LIFE -> Icons.Outlined.AutoAwesome
}

private const val SLOTS = 16
private const val COLUMNS = 4

@Composable
fun InventoryScreen(state: UiState, onBuy: (Item) -> Unit, onUse: (Item) -> Unit, startInShop: Boolean = false, openItem: Item? = null) {
    val sys = LocalSys.current
    val p = state.player ?: return
    var shop by rememberSaveable { mutableStateOf(startInShop) }
    var selected by remember { mutableStateOf(openItem) }
    val owned = Item.entries.filter { (state.inventory[it.id] ?: 0) > 0 }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Pane(
            label = if (shop) "Shop" else "Inventory",
            emphasis = true,
            trailing = {
                Text("G ", style = SysType.Num.copy(color = Palette.Gold))
                Text("%,d".format(p.gold), style = SysType.Num.copy(color = sys.text))
            },
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Inventory", !shop, { shop = false })
                Chip("Shop", shop, { shop = true })
            }
            Spacer(Modifier.height(14.dp))
            val items: List<Item?> = if (shop) Item.entries else owned
            val cells = items + List((SLOTS - items.size).coerceAtLeast(0)) { null }
            cells.chunked(COLUMNS).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                    row.forEach { item ->
                        Slot(
                            item = item,
                            count = item?.let { state.inventory[it.id] ?: 0 } ?: 0,
                            price = if (shop) item?.price else null,
                            affordable = item == null || !shop || p.gold >= item.price,
                            modifier = Modifier.weight(1f),
                        ) { if (item != null) selected = item }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Hairline()
            Spacer(Modifier.height(10.dp))
            Text(
                if (shop) "Earn gold by clearing quests, surviving penalties, passing trials and losing weight."
                else if (owned.isEmpty()) "Empty. Clear quests to earn gold, then visit the Shop."
                else "Tap an item to inspect or use it.",
                style = SysType.Small.copy(color = sys.muted),
            )
        }
        Pane(label = "Earning gold") {
            GoldRow("Daily Quest cleared", "20+")
            GoldRow("7-day streak", "+50")
            GoldRow("Penalty survived", "+15")
            GoldRow("Each kg lost", "+30")
            GoldRow("Rank-Up Trial passed", "100+")
        }
        Spacer(Modifier.height(8.dp))
    }
    selected?.let { item ->
        ItemCard(
            item = item,
            owned = state.inventory[item.id] ?: 0,
            gold = p.gold,
            onDismiss = { selected = null },
            onBuy = { onBuy(item) },
            onUse = { onUse(item); if ((state.inventory[item.id] ?: 0) <= 1) selected = null },
        )
    }
}

@Composable
private fun GoldRow(label: String, amount: String) {
    Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = SysType.Body.copy(color = LocalSys.current.text), modifier = Modifier.weight(1f))
        Text(amount, style = SysType.Num.copy(color = Palette.Gold))
    }
}

@Composable
private fun Slot(item: Item?, count: Int, price: Int?, affordable: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val sys = LocalSys.current
    val rarity = item?.rarity?.color?.let { Color(it) }
    val shape = systemShape(6.dp)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .background(Palette.Charcoal, shape)
                .border(1.dp, rarity?.copy(alpha = 0.7f) ?: sys.hairline, shape)
                .then(if (item != null) Modifier.clickable(onClick = onClick) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            if (item != null && rarity != null) {
                Icon(item.icon(), contentDescription = item.label, tint = if (affordable) rarity else Palette.Dim, modifier = Modifier.size(26.dp))
                // rarity strip
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth(0.5f)
                        .height(2.dp)
                        .background(rarity.copy(alpha = if (affordable) 1f else 0.3f)),
                )
                if (count > 0) {
                    Text(
                        "×$count",
                        style = SysType.Label.copy(color = sys.text, fontSize = 10.sp),
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(4.dp),
                    )
                }
            }
        }
        if (price != null) {
            Spacer(Modifier.height(4.dp))
            Text("%,d G".format(price), style = SysType.Label.copy(color = if (affordable) Palette.Gold else Palette.Dim, fontSize = 10.sp))
        }
    }
}

@Composable
private fun ItemCard(item: Item, owned: Int, gold: Int, onDismiss: () -> Unit, onBuy: () -> Unit, onUse: () -> Unit) {
    val sys = LocalSys.current
    val rarity = Color(item.rarity.color)
    Dialog(onDismissRequest = onDismiss) {
        SystemWindow(title = "Item", accent = rarity) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(56.dp)
                        .background(Palette.Charcoal, systemShape(6.dp))
                        .border(1.dp, rarity, systemShape(6.dp)),
                    contentAlignment = Alignment.Center,
                ) { Icon(item.icon(), contentDescription = null, tint = rarity, modifier = Modifier.size(30.dp)) }
                Column(Modifier.padding(start = 14.dp)) {
                    Text(item.label, style = SysType.Header.copy(color = sys.text))
                    Spacer(Modifier.height(4.dp))
                    Tag(item.rarity.label, rarity)
                }
            }
            Spacer(Modifier.height(14.dp))
            Text(item.description, style = SysType.Small.copy(color = sys.muted))
            Spacer(Modifier.height(10.dp))
            Row {
                Text("EFFECT  ", style = SysType.Label.copy(color = sys.muted))
                Text(item.effect, style = SysType.Body.copy(color = sys.text))
            }
            Spacer(Modifier.height(10.dp))
            Row {
                Text("OWNED  ", style = SysType.Label.copy(color = sys.muted))
                Text("$owned", style = SysType.Num.copy(color = sys.text), modifier = Modifier.weight(1f))
                Text("PRICE  ", style = SysType.Label.copy(color = sys.muted))
                Text("%,d G".format(item.price), style = SysType.Num.copy(color = Palette.Gold))
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SysButton("Buy", onBuy, Modifier.weight(1f), kind = ButtonKind.SECONDARY, enabled = gold >= item.price)
                SysButton("Use", onUse, Modifier.weight(1f), enabled = owned > 0, accent = rarity)
            }
        }
    }
}
