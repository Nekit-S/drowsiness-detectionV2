package com.drivermonitoring.dto;

import java.util.Map;

public class LabelEventDTO {
    private String driverId;
    private String sessionId;
    private long timestamp;
    private String label; // NORMAL, DROWSY, DISTRACTED
    private Map<String, Object> features; // агрегированные признаки
    private boolean isLabeled;

    public String getDriverId() { return driverId; }
    public void setDriverId(String driverId) { this.driverId = driverId; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }

    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }

    public Map<String, Object> getFeatures() { return features; }
    public void setFeatures(Map<String, Object> features) { this.features = features; }

    public boolean isLabeled() { return isLabeled; }
    public void setLabeled(boolean labeled) { isLabeled = labeled; }
}
