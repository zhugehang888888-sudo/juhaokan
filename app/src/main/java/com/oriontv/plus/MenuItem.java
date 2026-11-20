package com.oriontv.plus;

public class MenuItem {

    public String label;
    public String value;
    public Runnable action;

    public MenuItem(String label, String value, Runnable action) {
        this.label = label;
        this.value = value;
        this.action = action;
    }
}
