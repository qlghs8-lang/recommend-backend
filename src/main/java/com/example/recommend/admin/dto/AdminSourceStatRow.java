package com.example.recommend.admin.dto;

public class AdminSourceStatRow {
    private final String source;
    private final long count;

    public AdminSourceStatRow(String source, long count) {
        this.source = source;
        this.count = count;
    }

    public String getSource() { return source; }
    public long getCount() { return count; }
}
