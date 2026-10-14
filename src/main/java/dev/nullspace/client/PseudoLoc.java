package dev.nullspace.client;

// makes translated strings about 40% longer, like german, to check that labels still fit
final class PseudoLoc {
    private PseudoLoc() {}

    static String expand(String text) {
        StringBuilder out = new StringBuilder("[");
        int letters = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '%') {
                int j = i + 1;
                while (j < text.length() && (Character.isDigit(text.charAt(j)) || text.charAt(j) == '$')) j++;
                if (j < text.length()) j++;
                out.append(text, i, j);
                i = j - 1;
                continue;
            }
            out.append(c);
            if ("aeiouAEIOU".indexOf(c) >= 0) out.append(c);
            if (c != '\n') letters++;
        }
        int want = (int) Math.ceil(letters * 0.4), have = out.length() - 1 - letters;
        for (int i = have; i < want && letters > 3; i++) out.append(i % 3 == 0 ? "x" : i % 3 == 1 ? "y" : "z");
        return out.append(']').toString();
    }
}
