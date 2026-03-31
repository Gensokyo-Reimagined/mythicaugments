package net.taiyou.mythicaugments;

import io.lumine.mythic.api.adapters.AbstractEntity;
import io.lumine.mythic.api.adapters.AbstractLocation;
import io.lumine.mythic.api.skills.IParentSkill;
import io.lumine.mythic.api.skills.SkillCaster;
import io.lumine.mythic.api.skills.SkillMetadata;
import io.lumine.mythic.api.skills.SkillTrigger;
import io.lumine.mythic.core.skills.SkillMechanic;
import io.lumine.mythic.core.skills.SkillMetadataImpl;
import io.lumine.mythic.core.skills.TriggeredSkill;
import io.lumine.mythiccrucible.items.SkillHolder;

import java.util.*;
import java.util.function.Consumer;

public class AugmentSkillHolder implements SkillHolder {

    private final Map<SkillTrigger, Queue<SkillMechanic>> mechanics = new HashMap<>();
    private final Queue<SkillMechanic> timerMechanics = new LinkedList<>();

    public void clear() {
        mechanics.clear();
        timerMechanics.clear();
    }

    public void addMechanic(SkillTrigger trigger, SkillMechanic mechanic) {
        mechanics.computeIfAbsent(trigger, k -> new LinkedList<>()).add(mechanic);
    }

    public void addTimerMechanic(SkillMechanic mechanic) {
        timerMechanics.add(mechanic);
    }

    @Override
    public boolean hasTimerSkills() {
        return !timerMechanics.isEmpty();
    }

    @Override
    public void runTimerSkills(SkillCaster caster, long timer) {
        for (SkillMechanic ms : timerMechanics) {
            if (ms.getTimerInterval() > 0 && timer % ms.getTimerInterval() == 0) {
                SkillMetadataImpl data = new SkillMetadataImpl(
                        io.lumine.mythic.core.skills.SkillTriggers.TIMER,
                        caster, caster.getEntity());
                data.setCallingEvent(new TriggeredSkill(data));
                if (ms.isUsableFromCaster(data)) {
                    ms.execute(data);
                }
            }
        }
    }

    @Override
    public void runSkills(IParentSkill parent, SkillMetadata data) {
        Queue<SkillMechanic> skills = getSkills(data.getCause());
        if (skills == null || skills.isEmpty()) return;

        for (SkillMechanic ms : skills) {
            if (ms.isUsableFromCaster(data)) {
                ms.execute(data);
            }
        }
    }

    @Override
    public boolean runSkills(SkillCaster caster, SkillTrigger cause, AbstractLocation origin,
                             AbstractEntity trigger, Consumer<SkillMetadata> transformer) {
        Queue<SkillMechanic> skills = getSkills(cause);
        if (skills == null || skills.isEmpty()) return false;

        SkillMetadataImpl data = new SkillMetadataImpl(cause, caster, trigger);
        if (origin != null) {
            data.setOrigin(origin);
        }
        data.setPower(caster.getPower());

        TriggeredSkill ts = new TriggeredSkill(data);

        if (transformer != null) {
            transformer.accept(data);
        }

        for (SkillMechanic ms : skills) {
            if (ms.isUsableFromCaster(data)) {
                ms.execute(data);
            }
        }

        return ts.isCancel();
    }

    @Override
    public Queue<SkillMechanic> getSkills(SkillTrigger cause) {
        return mechanics.getOrDefault(cause, new LinkedList<>());
    }
}
