package com.adobe.clawdea.cost

import com.adobe.clawdea.provider.openai.profile.TokenRates
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class ModelPricingTest {

    @Test
    fun `prices opus input and output at published rates`() {
        // Opus 4.8: $5 / 1M input, $25 / 1M output.
        // 1M input + 1M output = 5 + 25 = 30.0
        val cost = ModelPricing.costFor("claude-opus-4-8", 1_000_000, 1_000_000, 0, 0)
        assertEquals(30.0, cost, 1e-9)
    }

    @Test
    fun `cache read billed at one tenth of input`() {
        // Sonnet 4.6 input $3/1M. 1M cache-read tokens = 3 * 0.1 = 0.30
        val cost = ModelPricing.costFor("claude-sonnet-4-6", 0, 0, 1_000_000, 0)
        assertEquals(0.30, cost, 1e-9)
    }

    @Test
    fun `cache creation billed at 1_25x input`() {
        // Haiku 4.5 input $1/1M. 1M cache-creation tokens = 1 * 1.25 = 1.25
        val cost = ModelPricing.costFor("claude-haiku-4-5", 0, 0, 0, 1_000_000)
        assertEquals(1.25, cost, 1e-9)
    }

    @Test
    fun `fable 5 priced at premium rates`() {
        // Fable 5: $10/1M input, $50/1M output.
        val cost = ModelPricing.costFor("claude-fable-5", 1_000_000, 1_000_000, 0, 0)
        assertEquals(60.0, cost, 1e-9)
    }

    @Test
    fun `mythos 5 priced at the fable tier`() {
        // Mythos 5 shares Fable's tier: $10/1M input, $50/1M output.
        val cost = ModelPricing.costFor("claude-mythos-5", 1_000_000, 1_000_000, 0, 0)
        assertEquals(60.0, cost, 1e-9)
    }

    @Test
    fun `mythos preview inherits the mythos tier`() {
        // "claude-mythos-preview" matches the generic claude-mythos prefix (10/50).
        val cost = ModelPricing.costFor("claude-mythos-preview", 1_000_000, 0, 0, 0)
        assertEquals(10.0, cost, 1e-9)
    }

    @Test
    fun `sonnet 5 uses introductory pricing before 2026-09-01`() {
        // Introductory: $2/1M input, $10/1M output.
        val r = ModelPricing.rateFor("claude-sonnet-5", LocalDate.of(2026, 7, 6))
        assertEquals(2.0, r.inputPerM, 1e-9)
        assertEquals(10.0, r.outputPerM, 1e-9)
    }

    @Test
    fun `sonnet 5 reverts to standard pricing on 2026-09-01`() {
        // Standard from Sep 1: $3/1M input, $15/1M output.
        val r = ModelPricing.rateFor("claude-sonnet-5", LocalDate.of(2026, 9, 1))
        assertEquals(3.0, r.inputPerM, 1e-9)
        assertEquals(15.0, r.outputPerM, 1e-9)
    }

    @Test
    fun `unknown model falls back to a default rate not zero`() {
        // Unknown models must NOT silently price to 0 (that would hide cost).
        // Fallback is the Opus rate (most conservative high estimate).
        val cost = ModelPricing.costFor("some-future-model", 1_000_000, 0, 0, 0)
        assertEquals(5.0, cost, 1e-9)
    }

    @Test
    fun `gpt-5 class models priced at the GPT-5 tier`() {
        // GPT-5 tier: $1.25/1M input, $10/1M output. 1M + 1M = 11.25
        assertEquals(11.25, ModelPricing.costFor("gpt-5", 1_000_000, 1_000_000, 0, 0), 1e-9)
        assertEquals(11.25, ModelPricing.costFor("gpt-5-codex", 1_000_000, 1_000_000, 0, 0), 1e-9)
        assertEquals(11.25, ModelPricing.costFor("gpt-5.6-sol", 1_000_000, 1_000_000, 0, 0), 1e-9)
        assertEquals(11.25, ModelPricing.costFor("gpt-5.4", 1_000_000, 1_000_000, 0, 0), 1e-9)
    }

    @Test
    fun `gpt mini variants priced at the mini tier`() {
        // Mini: $0.25/1M input, $2/1M output. 1M + 1M = 2.25
        assertEquals(2.25, ModelPricing.costFor("gpt-5-mini", 1_000_000, 1_000_000, 0, 0), 1e-9)
        assertEquals(2.25, ModelPricing.costFor("gpt-5.4-mini", 1_000_000, 1_000_000, 0, 0), 1e-9)
    }

    @Test
    fun `gpt nano variants priced at the nano tier`() {
        // Nano: $0.05/1M input, $0.40/1M output. 1M + 1M = 0.45
        assertEquals(0.45, ModelPricing.costFor("gpt-5-nano", 1_000_000, 1_000_000, 0, 0), 1e-9)
    }

    @Test
    fun `gpt models are not priced at the claude fallback`() {
        // Regression: before the GPT branch, gpt-* fell through to the Opus fallback (5/25).
        val r = ModelPricing.rateFor("gpt-5.6-terra")
        assertEquals(1.25, r.inputPerM, 1e-9)
        assertEquals(10.0, r.outputPerM, 1e-9)
    }

    @Test
    fun `model id matched case insensitively and by prefix`() {
        // Real ids may carry suffixes e.g. claude-opus-4-8-20260101
        val a = ModelPricing.costFor("claude-opus-4-8-20260101", 1_000_000, 0, 0, 0)
        assertEquals(5.0, a, 1e-9)
    }

    @Test
    fun `configured compatible rates include cached and reasoning tokens`() {
        val rates = TokenRates(inputPerM = 1.0, outputPerM = 2.0, cachedInputPerM = 0.2, reasoningPerM = 3.0)
        assertEquals(
            0.0062,
            ModelPricing.costFor(rates, input = 1000, output = 1000, cachedInput = 1000, reasoning = 1000),
            0.0000001,
        )
    }
}
