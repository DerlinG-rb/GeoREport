package com.example.georeport.data

import android.util.Patterns

object AuthValidator {

    const val MIN_PASSWORD_LENGTH = 6
    const val MIN_PHONE_DIGITS = 7

    fun isValidEmail(email: String): Boolean =
        Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches()

    fun isValidPassword(password: String): Boolean =
        password.length >= MIN_PASSWORD_LENGTH

    fun isValidPhone(phone: String): Boolean =
        phone.count { it.isDigit() } >= MIN_PHONE_DIGITS
}
