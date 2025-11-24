package net.taiyou.mythicaugments;

public class AugmentSkill {
    private final String skillLine;
    private final int interval;
    private long lastExecuted;

    public AugmentSkill(String skillLine, int interval) {
        this.skillLine = skillLine;
        this.interval = interval;
        this.lastExecuted = 0;
    }

    public String getSkillLine() {
        return skillLine;
    }

    public int getInterval() {
        return interval;
    }

    public long getLastExecuted() {
        return lastExecuted;
    }

    public void setLastExecuted(long lastExecuted) {
        this.lastExecuted = lastExecuted;
    }
}
