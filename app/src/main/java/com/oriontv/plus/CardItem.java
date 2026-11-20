package com.oriontv.plus;

public class CardItem {

    public String title = "";
    public String poster = "";
    public String badge = "";
    public String sub = "";
    public String source = "";
    public String id = "";
    public String searchTitle = "";
    public String year = "";
    public int index = -1;
    public int playTime = 0;
    public boolean resume = false;
    /** 收藏批量管理时是否勾选 */
    public boolean checked = false;

    public static CardItem of(VideoItem v) {
        CardItem c = new CardItem();
        c.title = v.title;
        c.poster = v.poster;
        c.source = v.source;
        c.id = v.id;
        c.searchTitle = v.title;
        c.year = v.year;
        c.badge = v.rate;
        if (v.episodes.size() > 1) {
            c.sub = v.sourceName + " · " + v.episodes.size() + "集";
        } else if (!v.typeName.isEmpty()) {
            c.sub = v.typeName;
        }
        return c;
    }
}
