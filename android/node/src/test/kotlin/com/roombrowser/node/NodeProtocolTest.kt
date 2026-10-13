package com.roombrowser.node

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertThrows
import org.junit.Test

class NodeProtocolTest {

    @Test
    fun request_carries_every_field_the_runtime_checks() {
        val sent = Json.parseToJsonElement(
            NodeProtocol.request(7, "status", "tok", "UA/1")
        ).jsonObject
        assertThat(sent["id"].toString()).isEqualTo("7")
        assertThat(sent["cmd"].toString()).isEqualTo("\"status\"")
        assertThat(sent["token"].toString()).isEqualTo("\"tok\"")
        assertThat(sent["userAgent"].toString()).isEqualTo("\"UA/1\"")
    }

    @Test
    fun a_missing_identity_is_left_out_rather_than_sent_blank() {
        val absent = Json.parseToJsonElement(
            NodeProtocol.request(1, "status", "tok", null)
        ).jsonObject
        assertThat(absent.containsKey("userAgent")).isFalse()
        val blank = Json.parseToJsonElement(
            NodeProtocol.request(1, "status", "tok", "   ")
        ).jsonObject
        assertThat(blank.containsKey("userAgent")).isFalse()
    }

    @Test
    fun a_reply_is_read_back_as_its_result() {
        val result = NodeProtocol.result("""{"id":3,"ok":true,"result":{"node":"v18.20.4"}}""", 3)
        assertThat(result["node"].toString()).isEqualTo("\"v18.20.4\"")
    }

    @Test
    fun the_runtimes_own_refusal_is_raised_with_its_words() {
        val thrown = assertThrows(IllegalStateException::class.java) {
            NodeProtocol.result("""{"id":3,"ok":false,"error":"no such command: nope"}""", 3)
        }
        assertThat(thrown.message).isEqualTo("no such command: nope")
    }

    @Test
    fun an_answer_to_a_different_request_is_refused() {
        val thrown = assertThrows(IllegalArgumentException::class.java) {
            NodeProtocol.result("""{"id":9,"ok":true,"result":{}}""", 3)
        }
        assertThat(thrown.message).contains("9")
    }

    @Test
    fun status_decodes_every_field_the_runtime_reports() {
        val result = NodeProtocol.result(
            """{"id":1,"ok":true,"result":{"node":"v18.20.4","platform":"linux","arch":"x64",""" +
                """"pid":42,"workDir":"/data","userAgent":"UA/1","userAgentEnforced":true}}""",
            1
        )
        val status = NodeProtocol.statusOf(result)
        assertThat(status.node).isEqualTo("v18.20.4")
        assertThat(status.platform).isEqualTo("linux")
        assertThat(status.arch).isEqualTo("x64")
        assertThat(status.pid).isEqualTo(42)
        assertThat(status.workDir).isEqualTo("/data")
        assertThat(status.userAgent).isEqualTo("UA/1")
        assertThat(status.userAgentEnforced).isTrue()
    }

    @Test
    fun a_runtime_that_reported_no_identity_decodes_as_none() {
        val result = NodeProtocol.result(
            """{"id":1,"ok":true,"result":{"node":"v18.20.4","platform":"linux","arch":"x64",""" +
                """"pid":42,"workDir":"/","userAgent":null,"userAgentEnforced":true}}""",
            1
        )
        val status = NodeProtocol.statusOf(result)
        assertThat(status.userAgent).isNull()
        assertThat(status.userAgentEnforced).isTrue()
    }
}
