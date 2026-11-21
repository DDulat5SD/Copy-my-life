package com.copymylife.copymylife.dto;

public class ProgressItemResponse {

    private String label;
    private int completed;
    private int total;
    private int percentage;

    public ProgressItemResponse(String label, int completed, int total) {
        this.label = label;
        this.completed = completed;
        this.total = total;
        this.percentage = total == 0 ? 0 : (int) Math.round((completed * 100.0) / total);
    }

    public String getLabel() { return label; }

    public int getCompleted() { return completed; }

    public int getTotal() { return total; }

    public int getPercentage() { return percentage; }
}