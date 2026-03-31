package net.taiyou.mythicaugments;

public class AugmentSkill {
    private final String skillLine;
    private final String trigger;
    private final int interval; // Only used for onTimer
    private long lastExecuted;

    public AugmentSkill(String skillLine, String trigger, int interval) {
        this.skillLine = skillLine;
        this.trigger = trigger;
        this.interval = interval;
        this.lastExecuted = 0;
    }

    public AugmentSkill(String skillLine, int interval) {
        this(skillLine, "onTimer", interval);
    }

    public String getSkillLine() {
        return skillLine;
    }

    public String getTrigger() {
        return trigger;
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
