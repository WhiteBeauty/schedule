package com.karyakina.schedule.util;

import java.util.Locale;

public class StringSimilarity {

    private StringSimilarity() {}

    public static double similarity(String a, String b) {
        if (a == null || b == null) return 0.0;
        String na = normalize(a);
        String nb = normalize(b);
        if (na.isEmpty() || nb.isEmpty()) return 0.0;
        if (na.equals(nb)) return 1.0;

        double levenshteinSim = 1.0 - (double) levenshteinDistance(na, nb) / Math.max(na.length(), nb.length());
        double jaroWinklerSim = jaroWinkler(na, nb);
        double tokenSim = tokenOverlapSimilarity(na, nb);

        return Math.max((levenshteinSim + jaroWinklerSim) / 2.0, tokenSim);
    }

    private static String normalize(String s) {
        return s.toLowerCase(Locale.ROOT)
                .replace('ё', 'е')
                .replaceAll("[.,]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static int levenshteinDistance(String a, String b) {
        int[][] dp = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) dp[i][0] = i;
        for (int j = 0; j <= b.length(); j++) dp[0][j] = j;
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                dp[i][j] = Math.min(Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1), dp[i - 1][j - 1] + cost);
            }
        }
        return dp[a.length()][b.length()];
    }

    private static double jaroWinkler(String a, String b) {
        double jaro = jaro(a, b);
        int prefixLength = 0;
        int maxPrefix = Math.min(4, Math.min(a.length(), b.length()));
        for (int i = 0; i < maxPrefix; i++) {
            if (a.charAt(i) == b.charAt(i)) prefixLength++;
            else break;
        }
        return jaro + prefixLength * 0.1 * (1 - jaro);
    }

    private static double jaro(String a, String b) {
        int aLen = a.length();
        int bLen = b.length();
        if (aLen == 0 && bLen == 0) return 1.0;
        if (aLen == 0 || bLen == 0) return 0.0;

        int matchDistance = Math.max(aLen, bLen) / 2 - 1;
        if (matchDistance < 0) matchDistance = 0;

        boolean[] aMatches = new boolean[aLen];
        boolean[] bMatches = new boolean[bLen];

        int matches = 0;
        for (int i = 0; i < aLen; i++) {
            int start = Math.max(0, i - matchDistance);
            int end = Math.min(i + matchDistance + 1, bLen);
            for (int j = start; j < end; j++) {
                if (bMatches[j] || a.charAt(i) != b.charAt(j)) continue;
                aMatches[i] = true;
                bMatches[j] = true;
                matches++;
                break;
            }
        }
        if (matches == 0) return 0.0;

        double transpositions = 0;
        int k = 0;
        for (int i = 0; i < aLen; i++) {
            if (!aMatches[i]) continue;
            while (!bMatches[k]) k++;
            if (a.charAt(i) != b.charAt(k)) transpositions++;
            k++;
        }
        transpositions /= 2;

        return ((matches / (double) aLen) + (matches / (double) bLen)
                + ((matches - transpositions) / matches)) / 3.0;
    }

    private static double tokenOverlapSimilarity(String a, String b) {
        String[] tokensA = a.split(" ");
        String[] tokensB = b.split(" ");
        if (tokensA.length == 0 || tokensB.length == 0) return 0.0;

        int matched = 0;
        boolean[] usedB = new boolean[tokensB.length];
        for (String ta : tokensA) {
            for (int j = 0; j < tokensB.length; j++) {
                if (usedB[j]) continue;
                String tb = tokensB[j];
                boolean sameToken = ta.equals(tb);
                boolean initialMatch = !ta.isEmpty() && !tb.isEmpty() && ta.charAt(0) == tb.charAt(0)
                        && (ta.length() == 1 || tb.length() == 1);
                if (sameToken || initialMatch) {
                    matched++;
                    usedB[j] = true;
                    break;
                }
            }
        }
        return matched / (double) Math.max(tokensA.length, tokensB.length);
    }
}
