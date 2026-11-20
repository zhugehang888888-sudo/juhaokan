package com.oriontv.plus;

public class SourceEntry {

    public final VideoItem item;
    public SourceTester.Result result;
    public boolean testing = true;
    public boolean selected = false;

    public SourceEntry(VideoItem item) {
        this.item = item;
    }
}
