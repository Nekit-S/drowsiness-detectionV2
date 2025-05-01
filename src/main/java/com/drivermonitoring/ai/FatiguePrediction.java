package com.drivermonitoring.ai;

public class FatiguePrediction {
    public enum RiskLevel { LOW, MEDIUM, HIGH }
    private RiskLevel riskLevel;
    private float probability; // [0,1]
    private int minutesUntilHigh;
    private String recommendation;
    private String dangerType; // Причина высокого риска: drowsy, distracted, both, none
    private float probabilityDrowsy; // вероятность сонливости
    private float probabilityDistracted; // вероятность отвлечения

    public FatiguePrediction(RiskLevel riskLevel, float probability, int minutesUntilHigh, String recommendation, String dangerType, float probabilityDrowsy, float probabilityDistracted) {
        this.riskLevel = riskLevel;
        this.probability = probability;
        this.minutesUntilHigh = minutesUntilHigh;
        this.recommendation = recommendation;
        this.dangerType = dangerType;
        this.probabilityDrowsy = probabilityDrowsy;
        this.probabilityDistracted = probabilityDistracted;
    }

    public RiskLevel getRiskLevel() { return riskLevel; }
    public void setRiskLevel(RiskLevel riskLevel) { this.riskLevel = riskLevel; }
    public float getProbability() { return probability; }
    public void setProbability(float probability) { this.probability = probability; }
    public int getMinutesUntilHigh() { return minutesUntilHigh; }
    public void setMinutesUntilHigh(int minutesUntilHigh) { this.minutesUntilHigh = minutesUntilHigh; }
    public String getRecommendation() { return recommendation; }
    public void setRecommendation(String recommendation) { this.recommendation = recommendation; }
    public String getDangerType() { return dangerType; }
    public void setDangerType(String dangerType) { this.dangerType = dangerType; }
    public float getProbabilityDrowsy() { return probabilityDrowsy; }
    public void setProbabilityDrowsy(float probabilityDrowsy) { this.probabilityDrowsy = probabilityDrowsy; }
    public float getProbabilityDistracted() { return probabilityDistracted; }
    public void setProbabilityDistracted(float probabilityDistracted) { this.probabilityDistracted = probabilityDistracted; }
}
