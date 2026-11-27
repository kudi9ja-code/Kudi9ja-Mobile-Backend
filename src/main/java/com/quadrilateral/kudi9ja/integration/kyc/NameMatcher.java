package com.quadrilateral.kudi9ja.integration.kyc;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Whether two renderings of a name are the same person.
 *
 * <p>An exact string comparison would be useless here and unfair to customers.
 * Nigerian names reach us in every order and spelling: a bank holds
 * {@code ADEYEMI CHIOMA}, the NIMC holds {@code Chioma Grace Adeyemi}, and the
 * customer types {@code Chioma Adeyemi}. All three are the same person, and
 * refusing a payout over word order would be a bug, not a control.
 *
 * <p>So the comparison is on the <b>set of name parts</b>, accent- and
 * punctuation-insensitive, and a match requires every part of the shorter name
 * to appear in the longer one. That accepts a dropped middle name and a
 * reordering, and still refuses a different person.
 */
public final class NameMatcher {

    /** Titles and suffixes carry no identity and would only cause false misses. */
    private static final Set<String> NOISE = Set.of(
            "mr", "mrs", "miss", "ms", "dr", "prof", "chief", "alhaji", "alhaja",
            "engr", "barr", "pastor", "rev", "jr", "snr", "sr", "ii", "iii");

    private NameMatcher() {
    }

    /**
     * Whether {@code claimed} and {@code official} name the same person.
     *
     * <p>Requires at least two matching parts when both names have two or more,
     * so a shared surname alone is never enough.
     */
    public static boolean matches(String claimed, String official) {
        return matches(claimed, official, false);
    }

    /**
     * As {@link #matches}, but a name part of five letters or more may differ
     * from its counterpart by one letter (added, dropped or changed, or two
     * neighbours swapped), so {@code Micheal} matches {@code Michael}; and a
     * part may be the short form of an Oluwa-/Olu- name, so {@code Segun}
     * matches {@code Oluwasegun}. Every other rule still holds.
     */
    public static boolean matchesAllowingTypo(String claimed, String official) {
        return matches(claimed, official, true);
    }

    private static boolean matches(String claimed, String official, boolean allowTypo) {
        Set<String> a = parts(claimed);
        Set<String> b = parts(official);
        if (a.isEmpty() || b.isEmpty()) {
            return false;
        }

        Set<String> shorter = a.size() <= b.size() ? a : b;
        Set<String> longer = a.size() <= b.size() ? b : a;

        Set<String> unused = new LinkedHashSet<>(longer);
        for (String part : shorter) {
            String hit = unused.contains(part) ? part : null;
            if (hit == null && allowTypo && part.length() >= 5) {
                hit = unused.stream()
                        .filter(other -> other.length() >= 5 && oneEditApart(part, other))
                        .findFirst()
                        .orElse(null);
            }
            if (hit == null && allowTypo) {
                hit = unused.stream()
                        .filter(other -> shortFormOf(part, other))
                        .findFirst()
                        .orElse(null);
            }
            if (hit == null) {
                return false;
            }
            unused.remove(hit);
        }
        // One shared word is a coincidence — half of Lagos shares a surname.
        return shorter.size() >= 2 || (a.size() == 1 && b.size() == 1);
    }

    /**
     * Whether one word is the other without its Yoruba "Oluwa"/"Olu" prefix:
     * Segun / Oluwasegun, Femi / Olufemi. The rest must be three letters or more.
     */
    static boolean shortFormOf(String x, String y) {
        String shorter = x.length() <= y.length() ? x : y;
        String longer = x.length() <= y.length() ? y : x;
        if (shorter.length() < 3) {
            return false;
        }
        return longer.equals("oluwa" + shorter) || longer.equals("olu" + shorter);
    }

    /** Whether two words differ by one inserted, dropped or changed letter, or one swap of neighbours. */
    static boolean oneEditApart(String x, String y) {
        if (x.equals(y) || Math.abs(x.length() - y.length()) > 1) {
            return false;
        }
        if (x.length() == y.length()) {
            int first = -1;
            int diffs = 0;
            for (int i = 0; i < x.length(); i++) {
                if (x.charAt(i) != y.charAt(i)) {
                    if (diffs == 0) {
                        first = i;
                    }
                    diffs++;
                }
            }
            if (diffs == 1) {
                return true;
            }
            // Neighbours swapped: Micheal / Michael
            return diffs == 2 && first + 1 < x.length()
                    && x.charAt(first) == y.charAt(first + 1)
                    && x.charAt(first + 1) == y.charAt(first);
        }
        String longer = x.length() > y.length() ? x : y;
        String shorter = x.length() > y.length() ? y : x;
        int i = 0;
        while (i < shorter.length() && shorter.charAt(i) == longer.charAt(i)) {
            i++;
        }
        return shorter.substring(i).equals(longer.substring(i + 1));
    }

    /** How much of the shorter name appeared in the longer one, 0 to 1. */
    public static double similarity(String claimed, String official) {
        Set<String> a = parts(claimed);
        Set<String> b = parts(official);
        if (a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        Set<String> shorter = a.size() <= b.size() ? a : b;
        Set<String> longer = a.size() <= b.size() ? b : a;
        long overlap = shorter.stream().filter(longer::contains).count();
        return (double) overlap / shorter.size();
    }

    /**
     * The meaningful words of a name, lowercased, stripped of accents,
     * hyphens, apostrophes and titles.
     */
    static Set<String> parts(String name) {
        if (name == null || name.isBlank()) {
            return Set.of();
        }
        String flattened = Normalizer.normalize(name, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z\\s]", " ");

        return Arrays.stream(flattened.split("\\s+"))
                .filter(part -> part.length() > 1)
                .filter(part -> !NOISE.contains(part))
                .collect(LinkedHashSet::new, Set::add, Set::addAll);
    }
}
