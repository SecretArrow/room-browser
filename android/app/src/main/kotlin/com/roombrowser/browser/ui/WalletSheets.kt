package com.roombrowser.browser.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.roombrowser.browser.wallet.DappDecision
import com.roombrowser.browser.wallet.DappRequest
import com.roombrowser.browser.wallet.NetworkRecord
import com.roombrowser.browser.wallet.WalletEngineApi
import com.roombrowser.domain.wallet.model.BroadcastResult
import com.roombrowser.domain.wallet.model.ChainType
import com.roombrowser.domain.wallet.model.FeeEstimate
import com.roombrowser.domain.wallet.model.NetworkConfig
import com.roombrowser.qr.QrCodeGenerator
import com.roombrowser.ui.common.EmptyState
import com.roombrowser.ui.common.LocalRoomExtras
import com.roombrowser.ui.common.RoomBottomSheetShape
import com.roombrowser.ui.common.RoomSheetHeader
import com.roombrowser.ui.common.SettingActionRow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.math.BigInteger

/**
 * Wallet sheets: the dApp confirmation sheets (also hosted by BrowserScreen —
 * every one of them is PUBLIC and self-contained: hand it the pending
 * [DappRequest] + the engine and it renders, decides and settles on its own)
 * plus the dashboard's own send / receive / account / network sheets.
 *
 * Sheet standard (Task 4-a tokens): [RoomBottomSheetShape] (10dp top
 * corners), the single centered M3 drag handle, 16dp horizontal gutters,
 * titleLarge header, 24dp tail spacer. M3's sheet dialog already lifts
 * content above the IME and pads above the nav bar (safeDrawing bottom) —
 * no sheet adds its own imePadding.
 *
 * SECURITY: no sheet ever renders key material. The only secret a sheet can
 * receive is a private key being TYPED by its owner (masked field). dApp
 * sheets show the WebView-verified host, never a page-claimed origin.
 */

// ---------------------------------------------------------------------------
// Shared sheet pieces
// ---------------------------------------------------------------------------

/** Quiet explanatory note — mirrors the settings screens' InfoNote look. */
@Composable
internal fun WalletInfoNote(text: String) {
    val extras = LocalRoomExtras.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape((extras.radius * 0.6f).dp))
            .background(extras.surfaceAlt.copy(alpha = 0.6f))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = extras.textSecondary
        )
    }
}

/**
 * Verified-host badge. Every dApp sheet names the host the BRIDGE verified
 * against the WebView (never the page's claimed origin), so the user always
 * signs for the site they think they are on.
 */
@Composable
private fun HostBadge(host: String) {
    val extras = LocalRoomExtras.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Filled.Verified,
            contentDescription = null,
            tint = extras.primary,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(8.dp))
        Column {
            Text(
                "Connected site",
                style = MaterialTheme.typography.labelSmall,
                color = extras.textSecondary
            )
            Text(
                host,
                style = MaterialTheme.typography.titleMedium,
                color = extras.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** One labeled key/value line inside a confirmation sheet. */
@Composable
private fun SheetDataRow(label: String, value: String) {
    val extras = LocalRoomExtras.current
    Column(Modifier.padding(vertical = 3.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = extras.textSecondary
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = extras.textPrimary
        )
    }
}

/** Scrollable monospace block for messages / raw JSON (bounded height). */
@Composable
private fun MonospaceBlock(text: String, maxHeight: Dp = 180.dp) {
    val extras = LocalRoomExtras.current
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight)
            .clip(RoundedCornerShape(10.dp))
            .background(extras.surfaceAlt.copy(alpha = 0.6f))
            .verticalScroll(rememberScrollState())
            .padding(10.dp)
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = extras.textPrimary
        )
    }
}

/** Approve (primary) / Reject (outlined) — the dApp decision row, ≥48dp targets. */
@Composable
private fun ApproveRejectButtons(onApprove: () -> Unit, onReject: () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Button(
            onClick = onApprove,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp)
        ) { Text("Approve") }
        OutlinedButton(
            onClick = onReject,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp)
        ) { Text("Reject") }
    }
}

/** "0x1234…abcd"-style shortening; short values pass through untouched. */
internal fun shortenAddress(address: String): String =
    if (address.length <= 12) address else address.take(6) + "…" + address.takeLast(4)

/**
 * Best-effort transaction URL for a network's block explorer (used by the
 * send-success snackbar and activity rows when no full URL was pre-computed).
 * Path convention per chain family; null when the network has no explorer.
 */
internal fun explorerTxUrl(config: NetworkConfig?, hash: String): String? {
    if (config == null) return null
    val base = config.explorerUrl?.trimEnd('/') ?: return null
    val path = when (config.chainType) {
        ChainType.EVM, ChainType.SOLANA, ChainType.BITCOIN -> "tx"
        ChainType.APTOS -> "txn"
        ChainType.SUI -> "txblock"
        ChainType.COSMOS -> "txs"
        ChainType.TRON -> "#/transaction"
    }
    return "$base/$path/$hash"
}

/** EVM-style address (0x + 40 hex). */
private val evmAddress = Regex("^0x[0-9a-fA-F]{40}$")

/** Base58 (Solana-style) address / key encoding. */
private val base58String = Regex("^[1-9A-HJ-NP-Za-km-z]{32,44}$")

/** Plain decimal amount with at most 18 fraction digits. */
private val decimalAmount = Regex("^\\d{1,18}(\\.\\d{1,18})?$")

/** URL shape accepted for RPC / explorer inputs. */
private val httpUrl = Regex("^https?://\\S+$")

/** UI-level recipient sanity check per chain family (the engine is authoritative). */
private fun isLikelyAddress(chain: ChainType, value: String): Boolean = when (chain) {
    ChainType.EVM -> evmAddress.matches(value)
    ChainType.SOLANA -> base58String.matches(value)
    ChainType.TRON -> value.length == 34 && value.startsWith("T")
    else -> value.length >= 10
}

/** Inline per-chain hint for the recipient field. */
private fun addressHint(chain: ChainType): String = when (chain) {
    ChainType.EVM -> "EVM address: 0x followed by 40 hex characters"
    ChainType.SOLANA -> "Solana address: 32–44 base58 characters"
    ChainType.TRON -> "TRON address: starts with T, 34 base58 characters"
    else -> "Check the ${chain.displayName} address format"
}

/** UI-level amount check: plain number, no exponents, ≤18 decimals. */
private fun isDecimalAmount(value: String): Boolean = decimalAmount.matches(value)

/**
 * Humanizes a chain-native token amount: hex ("0xde0b6b…") or decimal
 * base-units are divided by 10^[decimals]; anything unparseable is returned
 * as-is (the raw value is always safe to show).
 */
internal fun formatTxValue(raw: String, decimals: Int): String {
    val clean = raw.trim()
    val asBigInteger = when {
        clean.startsWith("0x") || clean.startsWith("0X") ->
            clean.drop(2).toBigIntegerOrNull(16)
        else -> clean.toBigIntegerOrNull()
    } ?: return clean
    return formatBaseUnits(asBigInteger, decimals)
}

private fun formatBaseUnits(units: BigInteger, decimals: Int): String {
    val safeDecimals = decimals.coerceIn(0, 36)
    val base = BigInteger.TEN.pow(safeDecimals)
    val whole = units.divide(base)
    val fraction = units.mod(base).toString().padStart(safeDecimals, '0').trimEnd('0')
    return if (fraction.isEmpty()) whole.toString() else "$whole.$fraction"
}

// ---------------------------------------------------------------------------
// dApp confirmation sheets (PUBLIC: BrowserScreen hosts them over the page)
// ---------------------------------------------------------------------------

/**
 * eth_requestAccounts & co. The account picker appears when the profile has
 * more than one account on the requested chain; Approve exposes the selected
 * account, Reject (and any dismissal) settles with USER_REJECTED.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectRequestSheet(
    request: DappRequest.Connect,
    engine: WalletEngineApi,
    onDismiss: () -> Unit
) {
    val accounts by engine.accounts.collectAsState()
    val chainAccounts = accounts.filter { it.chainType == request.chainType }
    var chosenId by remember(request.id) { mutableStateOf<String?>(null) }
    val selected = chainAccounts.firstOrNull { it.id == chosenId } ?: chainAccounts.firstOrNull()

    fun settle(approved: Boolean) {
        engine.decideDappRequest(
            DappDecision(
                requestId = request.id,
                approved = approved,
                chosenAccountId = if (approved) selected?.id else null
            )
        )
        onDismiss()
    }

    ModalBottomSheet(onDismissRequest = { settle(false) }, shape = RoomBottomSheetShape) {
        Column(
            Modifier
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            RoomSheetHeader("Connect site")
            HostBadge(request.host)
            Spacer(Modifier.height(8.dp))
            SheetDataRow("Chain", request.chainType.displayName)
            SheetDataRow("Origin", request.originUrl)
            Spacer(Modifier.height(10.dp))
            if (chainAccounts.size > 1) {
                Text(
                    "Account to share",
                    style = MaterialTheme.typography.labelMedium,
                    color = LocalRoomExtras.current.textSecondary
                )
                chainAccounts.forEach { account ->
                    RadioRow(
                        label = "${account.label} · ${shortenAddress(account.address)}",
                        selected = account.id == selected?.id,
                        onSelect = { chosenId = account.id }
                    )
                }
                Spacer(Modifier.height(10.dp))
            } else {
                selected?.let {
                    SheetDataRow("Account", "${it.label} · ${shortenAddress(it.address)}")
                }
            }
            WalletInfoNote(
                "Approving lets this site see the selected address and ask for " +
                    "transactions and signatures — every request still asks first."
            )
            Spacer(Modifier.height(16.dp))
            ApproveRejectButtons(
                onApprove = { settle(true) },
                onReject = { settle(false) }
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** personal_sign & co. The message body is scrollable monospace, never truncated silently. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignMessageSheet(
    request: DappRequest.SignMessage,
    engine: WalletEngineApi,
    onDismiss: () -> Unit
) {
    fun settle(approved: Boolean) {
        engine.decideDappRequest(DappDecision(requestId = request.id, approved = approved))
        onDismiss()
    }

    ModalBottomSheet(onDismissRequest = { settle(false) }, shape = RoomBottomSheetShape) {
        Column(
            Modifier
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            RoomSheetHeader("Sign message")
            HostBadge(request.host)
            Spacer(Modifier.height(8.dp))
            SheetDataRow("Account", shortenAddress(request.accountAddress))
            Spacer(Modifier.height(10.dp))
            MonospaceBlock(request.displayMessage)
            Spacer(Modifier.height(16.dp))
            ApproveRejectButtons(
                onApprove = { settle(true) },
                onReject = { settle(false) }
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * eth_signTypedData_v4 (EIP-712): domain + primaryType summary with the raw
 * JSON behind a "Show raw JSON" toggle for the full payload.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignTypedDataSheet(
    request: DappRequest.SignTypedData,
    engine: WalletEngineApi,
    onDismiss: () -> Unit
) {
    var rawShown by remember(request.id) { mutableStateOf(false) }
    val parsed = remember(request.typedDataJson) {
        runCatching { JSONObject(request.typedDataJson) }.getOrNull()
    }
    val domain = parsed?.optJSONObject("domain")
    val primaryType = parsed?.optString("primaryType")?.ifBlank { null }

    fun settle(approved: Boolean) {
        engine.decideDappRequest(DappDecision(requestId = request.id, approved = approved))
        onDismiss()
    }

    ModalBottomSheet(onDismissRequest = { settle(false) }, shape = RoomBottomSheetShape) {
        Column(
            Modifier
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            RoomSheetHeader("Sign typed data")
            HostBadge(request.host)
            Spacer(Modifier.height(8.dp))
            SheetDataRow("Account", shortenAddress(request.accountAddress))
            domain?.let {
                val parts = listOfNotNull(
                    it.optString("name").ifBlank { null },
                    it.optString("version").ifBlank { null }
                )
                if (parts.isNotEmpty()) SheetDataRow("Domain", parts.joinToString(" · "))
            }
            if (primaryType != null) SheetDataRow("Primary type", primaryType)
            Spacer(Modifier.height(6.dp))
            TextButton(onClick = { rawShown = !rawShown }) {
                Text(if (rawShown) "Hide raw JSON" else "Show raw JSON")
            }
            if (rawShown) MonospaceBlock(request.typedDataJson, maxHeight = 240.dp)
            Spacer(Modifier.height(16.dp))
            ApproveRejectButtons(
                onApprove = { settle(true) },
                onReject = { settle(false) }
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * eth_sendTransaction & co. Value is humanized from the chain's base units
 * when parseable; the calldata is truncated with an expand toggle; the fee
 * estimate comes pre-computed on the request (null = unknown/offline).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SendTransactionSheet(
    request: DappRequest.SendTransaction,
    engine: WalletEngineApi,
    onDismiss: () -> Unit
) {
    val networks by engine.networks.collectAsState()
    val network = networks.firstOrNull { it.config.id == request.networkId }?.config
    val decimals = network?.nativeDecimals ?: 18
    var dataExpanded by remember(request.id) { mutableStateOf(false) }
    val params = remember(request.txParamsJson) {
        runCatching { JSONObject(request.txParamsJson) }.getOrNull()
    }
    val to = params?.optString("to")?.ifBlank { null }
    val value = params?.optString("value")?.ifBlank { null }
    val data = params?.optString("data")?.ifBlank { null }

    fun settle(approved: Boolean) {
        engine.decideDappRequest(DappDecision(requestId = request.id, approved = approved))
        onDismiss()
    }

    ModalBottomSheet(onDismissRequest = { settle(false) }, shape = RoomBottomSheetShape) {
        Column(
            Modifier
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            RoomSheetHeader("Send transaction")
            HostBadge(request.host)
            Spacer(Modifier.height(8.dp))
            SheetDataRow("Network", network?.name ?: request.networkId)
            SheetDataRow("From", shortenAddress(request.accountAddress))
            if (to != null) SheetDataRow("To", shortenAddress(to))
            if (value != null) {
                val formatted = formatTxValue(value, decimals)
                val symbol = network?.nativeSymbol
                SheetDataRow("Value", if (symbol == null) formatted else "$formatted $symbol")
            }
            if (data != null) {
                SheetDataRow(
                    "Data",
                    if (dataExpanded || data.length <= 66) data else data.take(66) + "…"
                )
                if (data.length > 66) {
                    TextButton(onClick = { dataExpanded = !dataExpanded }) {
                        Text(if (dataExpanded) "Hide data" else "Show all data")
                    }
                }
            }
            SheetDataRow(
                "Fee estimate",
                request.feeEstimate?.let { "${it.estimatedCost} (${it.label})" } ?: "Unavailable"
            )
            Spacer(Modifier.height(16.dp))
            ApproveRejectButtons(
                onApprove = { settle(true) },
                onReject = { settle(false) }
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** wallet_switchEthereumChain: current network → requested network summary. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwitchChainSheet(
    request: DappRequest.SwitchChain,
    engine: WalletEngineApi,
    onDismiss: () -> Unit
) {
    val networks by engine.networks.collectAsState()
    val activeNetworks by engine.activeNetworks.collectAsState()
    val current = activeNetworks[request.chainType]
    val target = networks.firstOrNull { it.config.id == request.targetNetworkId }?.config

    fun settle(approved: Boolean) {
        engine.decideDappRequest(DappDecision(requestId = request.id, approved = approved))
        onDismiss()
    }

    ModalBottomSheet(onDismissRequest = { settle(false) }, shape = RoomBottomSheetShape) {
        Column(
            Modifier
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            RoomSheetHeader("Switch network")
            HostBadge(request.host)
            Spacer(Modifier.height(8.dp))
            SheetDataRow("Current", current?.name ?: "—")
            SheetDataRow("Requested", target?.name ?: request.targetNetworkId)
            target?.let {
                SheetDataRow("Chain ID", it.chainId)
                if (it.isTestnet) {
                    WalletInfoNote("This network is marked as a testnet.")
                }
            }
            Spacer(Modifier.height(16.dp))
            ApproveRejectButtons(
                onApprove = { settle(true) },
                onReject = { settle(false) }
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * wallet_addEthereumChain: the network the site proposes, as validated by
 * the engine. Approve lets the engine persist it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddChainSheet(
    request: DappRequest.AddChain,
    engine: WalletEngineApi,
    onDismiss: () -> Unit
) {
    val proposed = request.proposed

    fun settle(approved: Boolean) {
        engine.decideDappRequest(DappDecision(requestId = request.id, approved = approved))
        onDismiss()
    }

    ModalBottomSheet(onDismissRequest = { settle(false) }, shape = RoomBottomSheetShape) {
        Column(
            Modifier
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            RoomSheetHeader("Add network")
            HostBadge(request.host)
            Spacer(Modifier.height(8.dp))
            SheetDataRow("Name", proposed.name)
            SheetDataRow("Chain ID", proposed.chainId)
            SheetDataRow("Symbol", proposed.nativeSymbol)
            proposed.rpcUrls.firstOrNull()?.let { SheetDataRow("RPC URL", it) }
            proposed.explorerUrl?.let { SheetDataRow("Explorer", it) }
            if (proposed.isTestnet) {
                WalletInfoNote("This network is marked as a testnet.")
            }
            Spacer(Modifier.height(16.dp))
            ApproveRejectButtons(
                onApprove = { settle(true) },
                onReject = { settle(false) }
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * Dispatcher for the engine's pending-request queue: renders the right
 * confirmation sheet for ANY [DappRequest] without the host needing to know
 * the variant. Approve/Reject settle through the engine; dismissing the
 * sheet (back / outside tap) is a rejection (EIP-1193 UX).
 */
@Composable
fun WalletDappRequestSheet(
    request: DappRequest,
    engine: WalletEngineApi,
    onDismiss: () -> Unit
) {
    when (request) {
        is DappRequest.Connect -> ConnectRequestSheet(request, engine, onDismiss)
        is DappRequest.SignMessage -> SignMessageSheet(request, engine, onDismiss)
        is DappRequest.SignTypedData -> SignTypedDataSheet(request, engine, onDismiss)
        is DappRequest.SendTransaction -> SendTransactionSheet(request, engine, onDismiss)
        is DappRequest.SwitchChain -> SwitchChainSheet(request, engine, onDismiss)
        is DappRequest.AddChain -> AddChainSheet(request, engine, onDismiss)
    }
}

// ---------------------------------------------------------------------------
// Dashboard sheets
// ---------------------------------------------------------------------------

/**
 * Native send: account picker (when the chain has more than one), recipient
 * + amount with per-chain validation, a live (debounced) fee estimate, and a
 * Confirm that calls [WalletEngineApi.sendNative]. Errors stay INLINE in the
 * sheet so the typed inputs survive; success hands the hash + explorer URL
 * to [onSent] and closes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SendSheet(
    engine: WalletEngineApi,
    chainType: ChainType,
    onDismiss: () -> Unit,
    onSent: (hash: String, explorerUrl: String?) -> Unit
) {
    val extras = LocalRoomExtras.current
    val scope = rememberCoroutineScope()
    val accounts by engine.accounts.collectAsState()
    val activeNetworks by engine.activeNetworks.collectAsState()
    val chainAccounts = accounts.filter { it.chainType == chainType }
    var chosenId by remember { mutableStateOf<String?>(null) }
    val selected = chainAccounts.firstOrNull { it.id == chosenId } ?: chainAccounts.firstOrNull()
    val network = activeNetworks[chainType]
    var to by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var attempted by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var fee by remember { mutableStateOf<FeeEstimate?>(null) }
    var feeLoading by remember { mutableStateOf(false) }
    val fieldShape = RoundedCornerShape((extras.radius * 0.6f).dp)

    val toBlank = to.trim().isEmpty()
    val toInvalid = !toBlank && !isLikelyAddress(chainType, to.trim())
    val amountBlank = amount.isBlank()
    val amountInvalid = !amountBlank && !isDecimalAmount(amount.trim())
    val canSubmit = selected != null && network != null && !toBlank && !toInvalid &&
        !amountBlank && !amountInvalid && !sending

    // Fee estimate — debounced restart on every input change; an absent
    // result simply leaves the "Unavailable" row (offline-tolerant).
    LaunchedEffect(selected?.id, network?.id, to, amount) {
        if (selected == null || network == null || toBlank || amountBlank || amountInvalid) {
            fee = null
        } else {
            delay(300)
            feeLoading = true
            fee = runCatching {
                engine.estimateSendFee(chainType, network.id, selected.id, to.trim(), amount.trim())
            }.getOrNull()
            feeLoading = false
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, shape = RoomBottomSheetShape) {
        Column(
            Modifier
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            RoomSheetHeader("Send ${chainType.displayName}")
            if (network == null) {
                WalletInfoNote(
                    "No active ${chainType.displayName} network — pick one under Networks first."
                )
            }
            if (chainAccounts.size > 1) {
                Text(
                    "From account",
                    style = MaterialTheme.typography.labelMedium,
                    color = extras.textSecondary
                )
                chainAccounts.forEach { account ->
                    RadioRow(
                        label = "${account.label} · ${shortenAddress(account.address)}",
                        selected = account.id == selected?.id,
                        onSelect = { chosenId = account.id }
                    )
                }
                Spacer(Modifier.height(10.dp))
            }
            OutlinedTextField(
                value = to,
                onValueChange = { to = it },
                label = { Text("To address") },
                singleLine = true,
                isError = attempted && (toBlank || toInvalid),
                supportingText = {
                    when {
                        attempted && toBlank -> Text("Recipient address is required")
                        toInvalid -> Text(addressHint(chainType))
                    }
                },
                shape = fieldShape,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = amount,
                onValueChange = { amount = it },
                label = { Text("Amount (${network?.nativeSymbol ?: chainType.displayName})") },
                singleLine = true,
                isError = attempted && (amountBlank || amountInvalid),
                supportingText = {
                    when {
                        attempted && amountBlank -> Text("Amount is required")
                        amountInvalid -> Text("Enter a plain number, e.g. 0.01")
                    }
                },
                shape = fieldShape,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Fee estimate",
                    style = MaterialTheme.typography.labelMedium,
                    color = extras.textSecondary
                )
                Spacer(Modifier.width(8.dp))
                val currentFee = fee
                Text(
                    when {
                        feeLoading -> "Estimating…"
                        currentFee != null -> "${currentFee.estimatedCost} (${currentFee.label})"
                        else -> "Unavailable"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = extras.textPrimary
                )
            }
            failure?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
            Spacer(Modifier.height(16.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = {
                        attempted = true
                        val account = selected
                        val net = network
                        if (account == null || net == null) {
                            failure = null
                        } else {
                            sending = true
                            failure = null
                            scope.launch {
                                runCatching {
                                    engine.sendNative(
                                        accountId = account.id,
                                        networkId = net.id,
                                        to = to.trim(),
                                        amount = amount.trim()
                                    )
                                }.onSuccess { result ->
                                    sending = false
                                    when (result) {
                                        is BroadcastResult.Ok -> {
                                            onSent(result.hash, explorerTxUrl(net, result.hash))
                                            onDismiss()
                                        }
                                        is BroadcastResult.Error -> failure = result.message
                                    }
                                }.onFailure { e ->
                                    sending = false
                                    failure = e.message ?: "Send failed"
                                }
                            }
                        }
                    },
                    enabled = canSubmit,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                ) { Text(if (sending) "Sending…" else "Confirm") }
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                ) { Text("Cancel") }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * Receive: the account picker (when the chain has more than one), the full
 * address, its QR code and a copy button. Addresses are PUBLIC — the copy
 * is a plain clip (no sensitive flag), unlike password copies.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiveSheet(
    engine: WalletEngineApi,
    chainType: ChainType,
    onCopyAddress: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val accounts by engine.accounts.collectAsState()
    val chainAccounts = accounts.filter { it.chainType == chainType }
    var chosenId by remember { mutableStateOf<String?>(null) }
    val selected = chainAccounts.firstOrNull { it.id == chosenId } ?: chainAccounts.firstOrNull()
    val address = selected?.address

    ModalBottomSheet(onDismissRequest = onDismiss, shape = RoomBottomSheetShape) {
        Column(
            Modifier
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            RoomSheetHeader("Receive ${chainType.displayName}")
            if (chainAccounts.size > 1) {
                chainAccounts.forEach { account ->
                    RadioRow(
                        label = "${account.label} · ${shortenAddress(account.address)}",
                        selected = account.id == selected?.id,
                        onSelect = { chosenId = account.id }
                    )
                }
                Spacer(Modifier.height(10.dp))
            }
            if (address == null) {
                WalletInfoNote(
                    "No ${chainType.displayName} account yet — add one under Add account first."
                )
            } else {
                val qrBitmap = remember(address) { QrCodeGenerator.generate(address, 512) }
                AndroidView(
                    factory = { ctx ->
                        android.widget.ImageView(ctx).apply { setImageBitmap(qrBitmap) }
                    },
                    update = { it.setImageBitmap(qrBitmap) },
                    modifier = Modifier.size(220.dp)
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    address,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = LocalRoomExtras.current.textPrimary,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { onCopyAddress(address) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                ) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Copy address")
                }
                Spacer(Modifier.height(6.dp))
                WalletInfoNote(
                    "Your address is public — share it to receive ${chainType.displayName} " +
                        "funds. Never share your recovery phrase or private keys."
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * Per-chain network selector: radio rows over the chain's ENABLED networks
 * (tap = make active), plus entries into the custom-network form and the
 * Chainlist browser.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkPickerSheet(
    engine: WalletEngineApi,
    chainType: ChainType,
    onAddNetwork: () -> Unit,
    onBrowseChainlist: () -> Unit,
    onMessage: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val networks by engine.networks.collectAsState()
    val activeNetworks by engine.activeNetworks.collectAsState()
    val active = activeNetworks[chainType]
    val enabledNetworks = networks.filter { it.config.chainType == chainType && it.enabled }

    ModalBottomSheet(onDismissRequest = onDismiss, shape = RoomBottomSheetShape) {
        Column(
            Modifier
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            RoomSheetHeader("${chainType.displayName} network")
            if (enabledNetworks.isEmpty()) {
                WalletInfoNote(
                    "No enabled ${chainType.displayName} networks yet — add one below."
                )
            }
            enabledNetworks.forEach { record ->
                RadioRow(
                    label = record.config.name +
                        (if (record.config.isTestnet) " · testnet" else ""),
                    selected = record.config.id == active?.id,
                    onSelect = {
                        scope.launch {
                            runCatching { engine.setActiveNetwork(chainType, record.config.id) }
                                .onSuccess { onMessage("Network set to ${record.config.name}") }
                                .onFailure { onMessage("Could not switch network") }
                        }
                    }
                )
            }
            Spacer(Modifier.height(12.dp))
            SettingActionRow(
                title = "Add network",
                subtitle = "Add a custom EVM network by chain ID",
                leadingIcon = Icons.Filled.Add,
                onClick = onAddNetwork
            )
            SettingActionRow(
                title = "Browse Chainlist",
                subtitle = "Search the network catalog",
                leadingIcon = Icons.Filled.Search,
                onClick = onBrowseChainlist
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * Custom EVM network form: name, chain ID, RPC URL, symbol, decimals,
 * explorer — validated like the settings screens (inline errors, nothing
 * malformed is ever submitted to the engine).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddNetworkSheet(
    engine: WalletEngineApi,
    onMessage: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val extras = LocalRoomExtras.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var chainId by remember { mutableStateOf("") }
    var rpcUrl by remember { mutableStateOf("") }
    var symbol by remember { mutableStateOf("") }
    var decimals by remember { mutableStateOf("18") }
    var explorer by remember { mutableStateOf("") }
    var attempted by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    val fieldShape = RoundedCornerShape((extras.radius * 0.6f).dp)

    val nameBlank = name.trim().isEmpty()
    val chainIdInvalid = chainId.trim().toLongOrNull()?.let { it <= 0L } ?: true
    val rpcInvalid = !httpUrl.matches(rpcUrl.trim())
    val symbolBlank = symbol.trim().isEmpty()
    // 1..36, not 0..36: the engine rejects nativeDecimals <= 0 (a 0 would
    // otherwise surface as a misleading "already exists" failure).
    val decimalsInvalid = decimals.trim().toIntOrNull()?.let { it !in 1..36 } ?: false
    val explorerInvalid = explorer.isNotBlank() && !httpUrl.matches(explorer.trim())
    val canSubmit = !nameBlank && !chainIdInvalid && !rpcInvalid && !symbolBlank &&
        !decimalsInvalid && !explorerInvalid

    ModalBottomSheet(onDismissRequest = onDismiss, shape = RoomBottomSheetShape) {
        Column(
            Modifier
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            RoomSheetHeader("Add network")
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                isError = attempted && nameBlank,
                supportingText = {
                    if (attempted && nameBlank) Text("Name is required")
                },
                shape = fieldShape,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = chainId,
                onValueChange = { chainId = it },
                label = { Text("Chain ID (EVM)") },
                singleLine = true,
                isError = attempted && chainIdInvalid,
                supportingText = {
                    if (attempted && chainIdInvalid) Text("Numbers only, e.g. 137")
                },
                shape = fieldShape,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = rpcUrl,
                onValueChange = { rpcUrl = it },
                label = { Text("RPC URL (https://…)") },
                singleLine = true,
                isError = attempted && rpcInvalid,
                supportingText = {
                    if (attempted && rpcInvalid) Text("Must be an http(s) URL")
                },
                shape = fieldShape,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = symbol,
                onValueChange = { symbol = it },
                label = { Text("Native symbol") },
                singleLine = true,
                isError = attempted && symbolBlank,
                supportingText = {
                    if (attempted && symbolBlank) Text("Symbol is required, e.g. ETH")
                },
                shape = fieldShape,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = decimals,
                onValueChange = { decimals = it },
                label = { Text("Decimals") },
                singleLine = true,
                isError = attempted && decimalsInvalid,
                supportingText = {
                    if (attempted && decimalsInvalid) Text("1–36 (18 for most EVM chains)")
                },
                shape = fieldShape,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = explorer,
                onValueChange = { explorer = it },
                label = { Text("Explorer URL (optional)") },
                singleLine = true,
                isError = attempted && explorerInvalid,
                supportingText = {
                    if (attempted && explorerInvalid) Text("Must be an http(s) URL")
                },
                shape = fieldShape,
                modifier = Modifier.fillMaxWidth()
            )
            failure?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
            Spacer(Modifier.height(16.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = {
                        attempted = true
                        val id = chainId.trim().toLongOrNull()
                        if (id == null || id <= 0L || !canSubmit) {
                            failure = null
                        } else {
                            scope.launch {
                                val config = NetworkConfig.evm(
                                    chainId = id,
                                    name = name.trim(),
                                    rpcUrls = listOf(rpcUrl.trim()),
                                    symbol = symbol.trim(),
                                    explorer = explorer.trim().ifBlank { null }
                                ).copy(
                                    // The factory defaults to 18; honor the
                                    // field the user actually filled in.
                                    nativeDecimals = decimals.trim().toIntOrNull() ?: 18
                                )
                                runCatching { engine.addCustomNetwork(config) }
                                    .onSuccess { added ->
                                        if (added) {
                                            onMessage("Network added")
                                            onDismiss()
                                        } else {
                                            failure = "A network with this chain ID already exists"
                                        }
                                    }
                                    .onFailure { e ->
                                        failure = "Could not add network — ${e.message}"
                                    }
                            }
                        }
                    },
                    enabled = canSubmit,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                ) { Text("Add network") }
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                ) { Text("Cancel") }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * Chainlist browser: searchable list of every network the engine knows
 * (name / chain ID / symbol), an enable toggle per row, and a
 * "Refresh from Chainlist" action whose result count is reported through
 * [onMessage].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChainlistSheet(
    engine: WalletEngineApi,
    onMessage: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val extras = LocalRoomExtras.current
    val scope = rememberCoroutineScope()
    val networks by engine.networks.collectAsState()
    var query by remember { mutableStateOf("") }
    var refreshing by remember { mutableStateOf(false) }
    val needle = query.trim().lowercase()
    val filtered = networks.filter { record ->
        needle.isEmpty() ||
            record.config.name.lowercase().contains(needle) ||
            record.config.chainId.lowercase().contains(needle) ||
            record.config.nativeSymbol.lowercase().contains(needle)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, shape = RoomBottomSheetShape) {
        // No outer verticalScroll: the list below is the scrolling element.
        Column(Modifier.padding(horizontal = 16.dp)) {
            RoomSheetHeader("Chainlist browser")
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search networks") },
                singleLine = true,
                shape = RoundedCornerShape((extras.radius * 0.6f).dp),
                trailingIcon = {
                    Icon(Icons.Filled.Search, contentDescription = null, tint = extras.icon)
                },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            if (filtered.isEmpty()) {
                EmptyState(
                    "No matching networks",
                    "Nothing in the catalog matches \"$needle\"."
                )
            } else {
                LazyColumn(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                ) {
                    items(filtered, key = { it.config.id }) { record ->
                        ChainlistRow(record = record, onChange = { enabled ->
                            scope.launch {
                                runCatching { engine.setNetworkEnabled(record.config.id, enabled) }
                                    .onFailure { onMessage("Could not update network") }
                            }
                        })
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    scope.launch {
                        refreshing = true
                        runCatching { engine.refreshChainlist() }
                            .onSuccess { count ->
                                onMessage(
                                    if (count > 0) {
                                        "$count new networks from Chainlist"
                                    } else {
                                        "No new networks found"
                                    }
                                )
                            }
                            .onFailure { onMessage("Chainlist refresh failed") }
                        refreshing = false
                    }
                },
                enabled = !refreshing,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
            ) { Text(if (refreshing) "Refreshing…" else "Refresh from Chainlist") }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** One Chainlist row: name + chain/ID/symbol summary, whole row toggles enabled. */
@Composable
private fun ChainlistRow(record: NetworkRecord, onChange: (Boolean) -> Unit) {
    val extras = LocalRoomExtras.current
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape((extras.radius * 0.7f).dp))
            .clickable { onChange(!record.enabled) }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                record.config.name,
                style = MaterialTheme.typography.bodyLarge,
                color = extras.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                listOfNotNull(
                    record.config.chainType.displayName,
                    record.config.chainId.take(24),
                    record.config.nativeSymbol,
                    if (record.config.isTestnet) "testnet" else null,
                    if (record.isCustom) "custom" else null
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = extras.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        // Display-only: the whole row is the touch target (RadioRow pattern).
        Switch(checked = record.enabled, onCheckedChange = null)
    }
}

/**
 * Add account: one row per chain family (tap = derive the next account from
 * the wallet's recovery phrase), plus the entry into the private-key import.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddAccountSheet(
    engine: WalletEngineApi,
    onImportKey: () -> Unit,
    onMessage: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onDismissRequest = onDismiss, shape = RoomBottomSheetShape) {
        Column(
            Modifier
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            RoomSheetHeader("Add account")
            ChainType.entries.forEach { chain ->
                SettingActionRow(
                    title = "Add ${chain.displayName} account",
                    subtitle = "Derived from this wallet's recovery phrase",
                    leadingIcon = Icons.Filled.Add,
                    onClick = {
                        scope.launch {
                            runCatching { engine.addDerivedAccount(chain) }
                                .onSuccess { account ->
                                    if (account != null) {
                                        onMessage("${chain.displayName} account added")
                                        onDismiss()
                                    } else {
                                        onMessage(
                                            "Could not derive a ${chain.displayName} account"
                                        )
                                    }
                                }
                                .onFailure {
                                    onMessage("Could not derive a ${chain.displayName} account")
                                }
                        }
                    }
                )
            }
            SettingActionRow(
                title = "Import private key",
                subtitle = "Add an existing account by its private key",
                leadingIcon = Icons.Filled.Key,
                onClick = onImportKey
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Chain families that accept raw private-key imports (per the engine contract). */
private val keyImportChains = listOf(ChainType.EVM, ChainType.SOLANA, ChainType.TRON)

/** Per-chain hint for the private-key field. */
private fun privateKeyHint(chain: ChainType): String = when (chain) {
    ChainType.EVM, ChainType.TRON -> "64 hex characters, with or without the 0x prefix"
    ChainType.SOLANA -> "base58, 87–88 characters"
    else -> "Check the ${chain.displayName} key format"
}

/**
 * Private-key import: chain chips (EVM / Solana / TRON), a MASKED key field
 * (never rendered in clear by default — same reveal-toggle pattern as the
 * password vault) with per-chain hints, and an optional account label. The
 * typed key lives only in transient composition state.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportKeySheet(
    engine: WalletEngineApi,
    onMessage: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val extras = LocalRoomExtras.current
    val scope = rememberCoroutineScope()
    var chain by remember { mutableStateOf(ChainType.EVM) }
    var key by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    var keyVisible by remember { mutableStateOf(false) }
    var attempted by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    val fieldShape = RoundedCornerShape((extras.radius * 0.6f).dp)
    val keyBlank = key.trim().isEmpty()

    ModalBottomSheet(onDismissRequest = onDismiss, shape = RoomBottomSheetShape) {
        Column(
            Modifier
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            RoomSheetHeader("Import private key")
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                keyImportChains.forEach { candidate ->
                    FilterChip(
                        selected = candidate == chain,
                        onClick = { chain = candidate },
                        label = { Text(candidate.displayName) }
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = key,
                onValueChange = { key = it },
                label = { Text("Private key") },
                singleLine = true,
                visualTransformation = if (keyVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    IconButton(
                        onClick = { keyVisible = !keyVisible },
                        modifier = Modifier.semantics {
                            contentDescription =
                                if (keyVisible) "Hide private key" else "Show private key"
                        }
                    ) {
                        Icon(
                            if (keyVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = null,
                            tint = extras.icon
                        )
                    }
                },
                isError = attempted && keyBlank,
                supportingText = {
                    if (attempted && keyBlank) {
                        Text("Private key is required")
                    } else {
                        Text(privateKeyHint(chain))
                    }
                },
                shape = fieldShape,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text("Account label (optional)") },
                singleLine = true,
                shape = fieldShape,
                modifier = Modifier.fillMaxWidth()
            )
            failure?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
            Spacer(Modifier.height(16.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = {
                        attempted = true
                        scope.launch {
                            runCatching {
                                engine.importAccount(
                                    chainType = chain,
                                    privateKey = key.trim(),
                                    label = label.trim().ifBlank {
                                        "${chain.displayName} import"
                                    }
                                )
                            }.onSuccess { account ->
                                if (account != null) {
                                    onMessage("Key imported")
                                    onDismiss()
                                } else {
                                    failure = "Could not import this key — check it matches " +
                                        "the ${chain.displayName} format"
                                }
                            }.onFailure { e ->
                                failure = "Could not import this key — ${e.message}"
                            }
                        }
                    },
                    enabled = !keyBlank,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                ) { Text("Import key") }
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                ) { Text("Cancel") }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
