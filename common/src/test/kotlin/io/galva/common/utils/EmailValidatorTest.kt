package io.galva.common.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmailValidatorTest {
    @Test
    fun `accepts common addresses`() {
        listOf("peter@example.com", "first.last+tag@sub.example.co", "o'brien@example.ie", "a@b.io")
            .forEach { assertTrue(it, EmailValidator.isValid(it)) }
    }

    @Test
    fun `rejects malformed addresses`() {
        listOf(
            "", "plainaddress", "@example.com", "user@", "user@localhost", "user@example..com",
            "a@b@example.com", "user name@example.com", "user@exa mple.com", "user@ex_ample.com", "user\n@example.com",
        ).forEach { assertFalse(it, EmailValidator.isValid(it)) }
    }
}
