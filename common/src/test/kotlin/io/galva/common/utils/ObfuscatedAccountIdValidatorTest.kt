package io.galva.common.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ObfuscatedAccountIdValidatorTest {
    @Test
    fun `accepts ids Play takes`() {
        listOf("user_42", "0190f2c4-5b6e-7c3d-9a1b-2c3d4e5f6a7b", "a".repeat(64), "acct:42/eu")
            .forEach { assertTrue(it, ObfuscatedAccountIdValidator.isValid(it)) }
    }

    @Test
    fun `rejects blank, too long and email ids`() {
        listOf("", "   ", "a".repeat(65), "jane@example.com")
            .forEach { assertFalse(it, ObfuscatedAccountIdValidator.isValid(it)) }
    }
}
