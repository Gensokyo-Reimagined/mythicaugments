package net.taiyou.mythicaugments;

public class AugmentStat {
    private final String stat;
    private final double value;
    private final String type; // ADDITIVE, MULTIPLY, etc.

    public AugmentStat(String stat, double value, String type) {
        this.stat = stat.toUpperCase();
        this.value = value;
        this.type = type.toUpperCase();
    }

    public String getStat() {
        return stat;
    }

    public double getValue() {
        return value;
    }

    public String getType() {
        return type;
    }
}
