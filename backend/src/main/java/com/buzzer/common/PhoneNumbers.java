package com.buzzer.common;

import java.util.regex.Pattern;

/** Normalises Indian mobile numbers to E.164 (+91XXXXXXXXXX). */
public final class PhoneNumbers {
    private static final Pattern INDIAN_MOBILE = Pattern.compile("^[6-9]\\d{9}$");

    private PhoneNumbers() {}

    public static String normalise(String raw) {
        if (raw == null) throw BusinessException.invalid("Phone number is required");
        String digits = raw.replaceAll("[^0-9]", "");
        if (digits.length() == 12 && digits.startsWith("91")) digits = digits.substring(2);
        if (digits.length() == 11 && digits.startsWith("0")) digits = digits.substring(1);
        if (!INDIAN_MOBILE.matcher(digits).matches()) {
            throw BusinessException.invalid("Enter a valid 10-digit Indian mobile number");
        }
        return "+91" + digits;
    }

    public static String mask(String e164) {
        if (e164 == null || e164.length() < 4) return "****";
        return "******" + e164.substring(e164.length() - 4);
    }
}
