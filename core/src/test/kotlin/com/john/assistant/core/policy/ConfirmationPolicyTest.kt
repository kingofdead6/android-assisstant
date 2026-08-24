package com.john.assistant.core.policy

import com.john.assistant.core.fake.FakeTool
import com.john.assistant.core.tool.RiskLevel
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConfirmationPolicyTest {

    private val low = FakeTool("open_app", riskLevel = RiskLevel.LOW)
    private val medium = FakeTool("send_sms", riskLevel = RiskLevel.MEDIUM)
    private val high = FakeTool("make_payment", riskLevel = RiskLevel.HIGH)

    @Test
    fun `the balanced default asks from medium risk up`() {
        val policy = ConfirmationPolicy.BALANCED
        assertFalse(policy.requiresConfirmation(low))
        assertTrue(policy.requiresConfirmation(medium))
        assertTrue(policy.requiresConfirmation(high))
    }

    @Test
    fun `the cautious policy asks about everything`() {
        assertTrue(ConfirmationPolicy.CAUTIOUS.requiresConfirmation(low))
    }

    @Test
    fun `the relaxed policy still asks about high risk`() {
        val policy = ConfirmationPolicy.RELAXED
        assertFalse(policy.requiresConfirmation(medium))
        assertTrue(policy.requiresConfirmation(high))
    }

    @Test
    fun `a high risk tool cannot be waved through by configuration`() {
        val policy = ConfirmationPolicy(
            confirmFrom = RiskLevel.HIGH,
            neverConfirm = setOf("make_payment"),
        )
        assertTrue(policy.requiresConfirmation(high))
    }

    @Test
    fun `per-tool overrides apply below high risk`() {
        val policy = ConfirmationPolicy(
            confirmFrom = RiskLevel.MEDIUM,
            alwaysConfirm = setOf("open_app"),
            neverConfirm = setOf("send_sms"),
        )
        assertTrue(policy.requiresConfirmation(low))
        assertFalse(policy.requiresConfirmation(medium))
    }
}
