package com.example.recommend.chat.service;

import java.util.List;
import java.util.regex.Pattern;

/** Server-rendered snapshot; excluded from future provider history, retained in visible history. */
final class ReactionSummary {
    private static final String PREFIX = "내 반응(답변 시점): ";
    private static final Pattern REQUEST = Pattern.compile(
            "찜|싫어요|좋아요|북마크|\\b(?:bookmarks?|dislikes?|likes?)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern SUMMARY_LINE = Pattern.compile(
            "(?m)^" + Pattern.quote(PREFIX) + "[^\\r\\n]*(?:\\r?\\n|$)");

    private ReactionSummary() { }

    static boolean requested(String question) { return REQUEST.matcher(question).find(); }

    static String render(List<String> states) {
        if (states == null) throw ChatException.invalidResponse();
        return PREFIX + "좋아요 " + state(states, "LIKE") + " · 찜 " + state(states, "BOOKMARK")
                + " · 싫어요 " + state(states, "DISLIKE");
    }

    private static String state(List<String> states, String type) {
        return states.contains(type) ? "설정" : "미설정";
    }

    static String withoutSnapshots(String answer) { return SUMMARY_LINE.matcher(answer).replaceAll(""); }
}
