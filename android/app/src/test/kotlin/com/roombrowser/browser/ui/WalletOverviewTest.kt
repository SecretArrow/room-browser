package com.roombrowser.browser.ui

import com.google.common.truth.Truth.assertThat
import com.roombrowser.browser.wallet.WalletAccountRecord
import com.roombrowser.domain.wallet.model.ChainType
import org.junit.Test

/**
 * The dashboard header's "which chain, which account" rules.
 *
 * These are worth pinning because the unlocked dashboard is unreachable from
 * the instrumented suite: CI has no device credential, so the wallet renders
 * its locked pane and never composes its contents. Nothing else would catch a
 * header that offers Send for a chain the wallet holds no account on, or one
 * that acts from another chain's account — and the second is not a blank on
 * screen, it is the wrong address to send to.
 */
class WalletOverviewTest {

    private fun account(
        id: String,
        chain: ChainType,
        label: String = id
    ) = WalletAccountRecord(
        id = id,
        walletId = "w1",
        chainType = chain,
        address = "addr-$id",
        label = label,
        path = "m/44'/60'/0'/0/0",
        source = WalletAccountRecord.Source.DERIVED
    )

    // ------------------------------------------------- chainsHoldingAccounts

    @Test
    fun `chains come back in declaration order, not in the order accounts were added`() {
        val accounts = listOf(
            account("b", ChainType.BITCOIN),
            account("e", ChainType.EVM),
            account("s", ChainType.SOLANA)
        )

        assertThat(WalletOverview.chainsHoldingAccounts(accounts))
            .containsExactly(ChainType.EVM, ChainType.SOLANA, ChainType.BITCOIN)
            .inOrder()
    }

    @Test
    fun `a chain with two accounts is listed once`() {
        val accounts = listOf(
            account("e1", ChainType.EVM),
            account("e2", ChainType.EVM)
        )

        assertThat(WalletOverview.chainsHoldingAccounts(accounts))
            .containsExactly(ChainType.EVM)
    }

    @Test
    fun `no accounts means no chains`() {
        assertThat(WalletOverview.chainsHoldingAccounts(emptyList())).isEmpty()
    }

    // ------------------------------------------------------------ focusedChain

    @Test
    fun `the All filter focuses the first chain that holds an account`() {
        val accounts = listOf(
            account("s", ChainType.SOLANA),
            account("e", ChainType.EVM)
        )

        // Declaration order, so EVM wins even though Solana was added first.
        assertThat(WalletOverview.focusedChain(accounts, filter = null))
            .isEqualTo(ChainType.EVM)
    }

    @Test
    fun `a filter naming a held chain focuses that chain`() {
        val accounts = listOf(
            account("e", ChainType.EVM),
            account("s", ChainType.SOLANA)
        )

        assertThat(WalletOverview.focusedChain(accounts, filter = ChainType.SOLANA))
            .isEqualTo(ChainType.SOLANA)
    }

    @Test
    fun `a filter naming a chain with no account focuses nothing`() {
        val accounts = listOf(account("e", ChainType.EVM))

        // Not EVM: the user is looking at Aptos, so acting on EVM from the
        // header would be acting on a chain they did not select.
        assertThat(WalletOverview.focusedChain(accounts, filter = ChainType.APTOS)).isNull()
    }

    @Test
    fun `an empty wallet focuses nothing even with no filter`() {
        assertThat(WalletOverview.focusedChain(emptyList(), filter = null)).isNull()
        assertThat(WalletOverview.focusedChain(emptyList(), filter = ChainType.EVM)).isNull()
    }

    // ----------------------------------------------------------- activeAccount

    @Test
    fun `the selected account is the one the actions act from`() {
        val accounts = listOf(
            account("e1", ChainType.EVM),
            account("e2", ChainType.EVM)
        )

        assertThat(WalletOverview.activeAccount(accounts, ChainType.EVM, "e2")?.id)
            .isEqualTo("e2")
    }

    @Test
    fun `with nothing selected the chain's first account is used`() {
        val accounts = listOf(
            account("e1", ChainType.EVM),
            account("e2", ChainType.EVM)
        )

        assertThat(WalletOverview.activeAccount(accounts, ChainType.EVM, null)?.id)
            .isEqualTo("e1")
    }

    @Test
    fun `a selection that was since deleted falls back to the chain's first`() {
        val accounts = listOf(account("e1", ChainType.EVM))

        assertThat(WalletOverview.activeAccount(accounts, ChainType.EVM, "gone")?.id)
            .isEqualTo("e1")
    }

    @Test
    fun `a selection belonging to another chain never answers for this one`() {
        val accounts = listOf(
            account("e1", ChainType.EVM),
            account("s1", ChainType.SOLANA)
        )

        // An id-only lookup would hand back the Solana account here — a valid
        // address, on the wrong chain, with nothing on screen saying so.
        assertThat(WalletOverview.activeAccount(accounts, ChainType.EVM, "s1")?.id)
            .isEqualTo("e1")
    }

    @Test
    fun `a chain with no account has no active account`() {
        val accounts = listOf(account("e1", ChainType.EVM))

        assertThat(WalletOverview.activeAccount(accounts, ChainType.BITCOIN, null)).isNull()
        assertThat(WalletOverview.activeAccount(emptyList(), ChainType.EVM, "e1")).isNull()
    }
}
