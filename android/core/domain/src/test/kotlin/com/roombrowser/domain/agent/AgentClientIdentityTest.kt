package com.roombrowser.domain.agent

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The client-identity rule, covered here because the thing it works around —
 * AgentRouter refusing every non-CLI client — can only be reproduced against
 * the live provider, and a test that needs the network is a test that does not
 * run in CI. The accepted/refused spellings recorded in
 * [AgentClientIdentity]'s comment are the ones this pins.
 */
class AgentClientIdentityTest {

    // ------------------------------------------------------------- the gate

    @Test
    fun `agentrouter gets the cli identity`() {
        assertEquals(AgentClientIdentity.CLAUDE_CLI, AgentClientIdentity.userAgent("https://agentrouter.org/v1"))
    }

    @Test
    fun `a subdomain mirror is covered too`() {
        assertEquals(
            AgentClientIdentity.CLAUDE_CLI,
            AgentClientIdentity.userAgent("https://api.agentrouter.org/v1")
        )
    }

    @Test
    fun `the host is matched case-insensitively and with a port`() {
        assertEquals(
            AgentClientIdentity.CLAUDE_CLI,
            AgentClientIdentity.userAgent("HTTPS://AgentRouter.ORG:8443/v1")
        )
    }

    @Test
    fun `everything else gets the app's own identity`() {
        assertEquals(AgentClientIdentity.DEFAULT, AgentClientIdentity.userAgent("https://api.openai.com/v1"))
        assertEquals(AgentClientIdentity.DEFAULT, AgentClientIdentity.userAgent("https://api.z.ai/api/paas/v4"))
        assertEquals(AgentClientIdentity.DEFAULT, AgentClientIdentity.userAgent("http://localhost:11434/v1"))
    }

    /** The rule is a host, not a substring: a lookalike domain must not match. */
    @Test
    fun `a host that merely contains the name is not the provider`() {
        assertEquals(AgentClientIdentity.DEFAULT, AgentClientIdentity.userAgent("https://agentrouter.org.evil.test/v1"))
        assertEquals(AgentClientIdentity.DEFAULT, AgentClientIdentity.userAgent("https://notagentrouter.org/v1"))
        assertEquals(AgentClientIdentity.DEFAULT, AgentClientIdentity.userAgent("https://agentrouter.com/v1"))
    }

    // ------------------------------------------------------- host extraction

    @Test
    fun `the path, query and fragment are not part of the host`() {
        assertEquals(
            AgentClientIdentity.CLAUDE_CLI,
            AgentClientIdentity.userAgent("https://agentrouter.org/v1/messages?x=1#frag")
        )
    }

    @Test
    fun `userinfo does not displace the host`() {
        assertEquals(
            AgentClientIdentity.CLAUDE_CLI,
            AgentClientIdentity.userAgent("https://user:pw@agentrouter.org/v1")
        )
    }

    @Test
    fun `a schemeless base url is still read as a host`() {
        assertEquals(AgentClientIdentity.CLAUDE_CLI, AgentClientIdentity.userAgent("agentrouter.org/v1"))
    }

    @Test
    fun `surrounding whitespace is tolerated`() {
        assertEquals(
            AgentClientIdentity.CLAUDE_CLI,
            AgentClientIdentity.userAgent("  https://agentrouter.org/v1  ")
        )
    }

    /** The field is typed by hand: an empty or half-typed URL must not throw. */
    @Test
    fun `an unparseable base url falls back instead of failing`() {
        assertEquals(AgentClientIdentity.DEFAULT, AgentClientIdentity.userAgent(""))
        assertEquals(AgentClientIdentity.DEFAULT, AgentClientIdentity.userAgent("   "))
        assertEquals(AgentClientIdentity.DEFAULT, AgentClientIdentity.userAgent("https://"))
    }

    @Test
    fun `an ipv6 literal keeps its colons`() {
        assertEquals(AgentClientIdentity.DEFAULT, AgentClientIdentity.userAgent("http://[::1]:8080/v1"))
    }
}
