package com.roombrowser.browser.ui

import com.roombrowser.browser.wallet.WalletAccountRecord
import com.roombrowser.domain.wallet.model.ChainType

/**
 * Which chain the wallet's primary actions act on, and which account they act
 * from.
 *
 * WHY THIS IS NOT INLINE IN THE COMPOSABLE: the dashboard used to repeat a
 * Send / Receive pair under EVERY chain section, so a wallet holding five
 * chains offered five identical pairs and no single place to act — the user
 * had to find the right section before they could send anything. Lifting the
 * pair into one header means the dashboard now has to answer "which chain?"
 * as a real question, and the answer has rules: it follows the chain filter
 * when the filter names a chain that actually holds an account, falls back to
 * the first chain that does, and yields nothing at all when the filter names
 * a chain the wallet has no account on. Those rules are arithmetic on a list,
 * and every one of them is a plausible off-by-one — an account list that is
 * empty, a selection that points at a deleted account, a filter left on a
 * chain that was since removed.
 *
 * It is also why this is a pure object: the unlocked dashboard is unreachable
 * from the instrumented suite (CI has no device credential, so the wallet
 * renders its locked pane and never composes its contents), which means these
 * rules have no other way to be tested at all.
 */
internal object WalletOverview {

    /**
     * Every chain that holds at least one account, in [ChainType] declaration
     * order — which is the order the dashboard has always grouped them in, so
     * a wallet's sections do not reshuffle when an account is added.
     *
     * The order is deliberately not "most recently used": a list that reorders
     * itself moves the Send button the user is reaching for.
     */
    fun chainsHoldingAccounts(accounts: List<WalletAccountRecord>): List<ChainType> =
        ChainType.entries.filter { chain -> accounts.any { it.chainType == chain } }

    /**
     * The chain the header's Send / Receive act on.
     *
     * [filter] is the dashboard's chain filter, reused as the focus rather
     * than kept as a second piece of state: two selectors that mean almost the
     * same thing drift apart, and the user would then be looking at one chain
     * while the buttons acted on another. A filter of `null` means "All" and
     * focuses the first chain that holds an account.
     *
     * Returns `null` — never a chain with no account — when the filter names a
     * chain the wallet holds nothing on. The header then shows no account and
     * no actions, which is the truth, and the empty state below says why.
     */
    fun focusedChain(
        accounts: List<WalletAccountRecord>,
        filter: ChainType?
    ): ChainType? {
        val held = chainsHoldingAccounts(accounts)
        return if (filter == null) held.firstOrNull() else held.firstOrNull { it == filter }
    }

    /**
     * The account a chain's Send / Receive act from: the one the user picked,
     * or the chain's first account when they have picked none.
     *
     * The selection is matched on the account's CHAIN as well as its id. The
     * id alone would be enough today — the selection is only ever written by a
     * card in that chain's own section — but an id-only lookup returns another
     * chain's account the moment that stops being true, and an account from
     * the wrong chain is not a missing answer, it is a wrong address to send
     * to. Checking both makes that unrepresentable rather than unlikely.
     */
    fun activeAccount(
        accounts: List<WalletAccountRecord>,
        chain: ChainType,
        selectedId: String?
    ): WalletAccountRecord? =
        accounts.firstOrNull { it.id == selectedId && it.chainType == chain }
            ?: accounts.firstOrNull { it.chainType == chain }
}
