package com.quadrilateral.kudi9ja.common.util;

/**
 * What may leave the server. A BVN or NIN goes out as its last four; the only
 * full copy is the admin panel's logged reveal of one customer's numbers. An
 * account number shows its last four so a customer can recognise their own.
 */
public final class Masks {

    private Masks() {
    }

    /** Last four digits only: how every list and record returns a BVN or NIN. */
    public static String lastFour(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.length() <= 4) {
            return trimmed;
        }
        return "*".repeat(trimmed.length() - 4) + trimmed.substring(trimmed.length() - 4);
    }

    /** The form the ledger stores against a withdrawal. */
    public static String accountTail(String value) {
        if (value == null || value.length() <= 4) {
            return value == null ? "" : value;
        }
        return "******" + value.substring(value.length() - 4);
    }

    /** Enough of an address to recognise, not enough to harvest. */
    public static String email(String value) {
        if (value == null || !value.contains("@")) {
            return "";
        }
        int at = value.indexOf('@');
        String local = value.substring(0, at);
        String domain = value.substring(at);
        if (local.length() <= 3) {
            return local.charAt(0) + "***" + domain;
        }
        return local.substring(0, 3) + "*".repeat(local.length() - 3) + domain;
    }

    /**
     * Enough of a name to recognise, not enough to harvest.
     *
     * <p>"Chioma Grace Adeyemi" becomes "Chioma G. A.". A sender who meant
     * Chioma is reassured they have the right person; somebody walking the
     * customer-reference space to build a directory of names learns very
     * little.
     */
    public static String displayName(String fullName) {
        if (fullName == null || fullName.isBlank()) {
            return "";
        }
        String[] parts = fullName.trim().split("\\s+");
        StringBuilder masked = new StringBuilder(parts[0]);
        for (int i = 1; i < parts.length; i++) {
            masked.append(' ').append(Character.toUpperCase(parts[i].charAt(0))).append('.');
        }
        return masked.toString();
    }

    /** A phone number with only the last three digits showing. */
    public static String phone(String value) {
        if (value == null || value.length() <= 3) {
            return value == null ? "" : value;
        }
        return "*".repeat(value.length() - 3) + value.substring(value.length() - 3);
    }
}
