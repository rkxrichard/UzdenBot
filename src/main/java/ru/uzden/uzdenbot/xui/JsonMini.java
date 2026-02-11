package ru.uzden.uzdenbot.xui;

final class JsonMini {

    private JsonMini() {
    }

    static String extractFieldValue(String jsonObject, String field) {
        if (jsonObject == null) return null;
        String t = jsonObject.trim();
        if (!t.startsWith("{")) return null;

        int idx = indexOfField(t, field);
        if (idx < 0) return null;

        int colon = t.indexOf(':', idx);
        if (colon < 0) return null;

        int valStart = colon + 1;
        while (valStart < t.length() && Character.isWhitespace(t.charAt(valStart))) valStart++;

        return extractJsonValue(t, valStart);
    }

    static int indexOfField(String json, String field) {
        String needle = "\"" + field + "\"";
        int i = json.indexOf(needle);
        if (i < 0) return -1;
        return i + needle.length();
    }

    static String extractJsonValue(String s, int pos) {
        if (s == null || pos < 0 || pos >= s.length()) return null;
        int i = pos;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        if (i >= s.length()) return null;

        char c = s.charAt(i);
        if (c == '"') {
            int end = findStringEnd(s, i);
            if (end < 0) return null;
            return s.substring(i, end + 1);
        }
        if (c == '{' || c == '[') {
            int end = findMatchingBracket(s, i);
            if (end < 0) return null;
            return s.substring(i, end + 1);
        }
        int end = i;
        while (end < s.length() && ",}\n\r\t".indexOf(s.charAt(end)) < 0) end++;
        return s.substring(i, end).trim();
    }

    static int findStringEnd(String s, int startQuote) {
        boolean esc = false;
        for (int i = startQuote + 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (esc) {
                esc = false;
                continue;
            }
            if (c == '\\') {
                esc = true;
                continue;
            }
            if (c == '"') return i;
        }
        return -1;
    }

    static int findMatchingBracket(String s, int start) {
        char open = s.charAt(start);
        char close = (open == '{') ? '}' : ']';
        int depth = 0;
        boolean inStr = false;
        boolean esc = false;

        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);

            if (inStr) {
                if (esc) {
                    esc = false;
                    continue;
                }
                if (c == '\\') {
                    esc = true;
                    continue;
                }
                if (c == '"') {
                    inStr = false;
                }
                continue;
            }

            if (c == '"') {
                inStr = true;
                continue;
            }
            if (c == open) depth++;
            else if (c == close) {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    static String unquoteIfString(String jsonValue) {
        if (jsonValue == null) return null;
        String t = jsonValue.trim();
        if (t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"")) {
            String inner = t.substring(1, t.length() - 1);
            return unescapeJsonString(inner);
        }
        return jsonValue;
    }

    static String unescapeJsonString(String s) {
        if (s == null) return null;
        if (s.indexOf('\\') < 0) return s;

        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            if (i + 1 >= s.length()) {
                sb.append('\\');
                break;
            }
            char n = s.charAt(++i);
            switch (n) {
                case '"':
                    sb.append('"');
                    break;
                case '\\':
                    sb.append('\\');
                    break;
                case '/':
                    sb.append('/');
                    break;
                case 'b':
                    sb.append('\b');
                    break;
                case 'f':
                    sb.append('\f');
                    break;
                case 'n':
                    sb.append('\n');
                    break;
                case 'r':
                    sb.append('\r');
                    break;
                case 't':
                    sb.append('\t');
                    break;
                case 'u':
                    if (i + 4 < s.length()) {
                        String hex = s.substring(i + 1, i + 5);
                        try {
                            sb.append((char) Integer.parseInt(hex, 16));
                            i += 4;
                            break;
                        } catch (NumberFormatException ignore) {
                        }
                    }
                    sb.append('u');
                    break;
                default:
                    sb.append(n);
                    break;
            }
        }
        return sb.toString();
    }
}
