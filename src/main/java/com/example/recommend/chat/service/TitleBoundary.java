package com.example.recommend.chat.service;

import java.util.*;

/** Checks lexical quote boundaries only; never chooses a work or infers an unquoted title. */
final class TitleBoundary {
    private TitleBoundary() { }
    static final String QUESTION = "작품 제목의 범위를 확정하기 어려워요. 정확한 제목 전체를 따옴표로 묶거나 카탈로그 ID로 알려 주세요.";
    private static final Map<Character, Character> QUOTES = Map.of(
            '"', '"', '\'', '\'', '“', '”', '‘', '’', '「', '」', '『', '』', '〈', '〉', '《', '》');
    private record Open(char close, int start) { }
    private record Span(int start, int end, boolean closed) { }

    static boolean permits(String question, String title) {
        if (question == null || title == null || title.isBlank() || question.length() > 8000) return false;
        var stack = new ArrayDeque<Open>(); var spans = new ArrayList<Span>();
        for (int i = 0; i < question.length(); i++) {
            char c = question.charAt(i);
            if (!QUOTES.containsKey(c) && !QUOTES.containsValue(c)) continue;
            if (escaped(question, i) || apostropheWithinWord(question, i)) continue;
            if (!stack.isEmpty() && stack.peek().close() == c) {
                spans.add(new Span(stack.pop().start(), i, true));
                if (spans.size() > 128) return false;
            } else if (QUOTES.containsKey(c)) {
                // A trailing English possessive apostrophe is not an opening title delimiter.
                if (c == '\'' && i > 0 && latinWord(question.charAt(i - 1))) continue;
                if (stack.size() >= 32) return false;
                stack.push(new Open(QUOTES.get(c), i + 1));
            }
        }
        for (var open : stack) spans.add(new Span(open.start(), question.length(), false));
        for (int start = 0; start + title.length() <= question.length(); start++) {
            if (!question.regionMatches(true, start, title, 0, title.length())) continue;
            boolean cropped = false;
            for (var span : spans) if (start >= span.start() && start + title.length() <= span.end()) {
                if (!span.closed() || !question.substring(span.start(), span.end()).strip().equalsIgnoreCase(title)) {
                    cropped = true; break;
                }
            }
            // A separately mentioned unquoted title remains valid, even if the same word occurs inside another quote.
            if (!cropped) return true;
        }
        return false;
    }

    private static boolean latinWord(char c) {
        return Character.isDigit(c) || Character.UnicodeScript.of(c) == Character.UnicodeScript.LATIN;
    }
    private static boolean apostropheWithinWord(String text, int index) {
        char c = text.charAt(index);
        return (c == '\'' || c == '’') && index > 0 && index + 1 < text.length()
                && latinWord(text.charAt(index - 1)) && latinWord(text.charAt(index + 1));
    }
    private static boolean escaped(String text, int index) {
        int count = 0;
        while (--index >= 0 && text.charAt(index) == '\\') count++;
        return count % 2 == 1;
    }
}
