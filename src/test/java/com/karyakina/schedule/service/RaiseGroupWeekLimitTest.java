package com.karyakina.schedule.service;

import com.karyakina.schedule.domain.Discipline;
import com.karyakina.schedule.domain.StudyGroup;
import com.karyakina.schedule.domain.Teacher;
import com.karyakina.schedule.domain.TeacherLoad;
import com.karyakina.schedule.dto.MissingResourceRequest;
import com.karyakina.schedule.dto.ResolutionDecision;
import com.karyakina.schedule.service.generator.SolverConfig;
import com.karyakina.schedule.service.generator.SolverInput;
import com.karyakina.schedule.service.generator.SolverResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class RaiseGroupWeekLimitTest {

    private final ScheduleGeneratorService service = new ScheduleGeneratorService(
            null, null, null, null, null, null, null, null, null, null);

    @Test
    void adminCanRaiseGroupWeekLimitAndItAppliesToBuildGroups() {
        StudyGroup group = StudyGroup.builder().id(1L).name("№ 4 Механизаторы").studentCount(20).build();
        Teacher teacher = Teacher.builder().id(1L).fullName("Гисцева Е.И.").build();
        Discipline discipline = Discipline.builder().id(1L).name("Физика").build();
        TeacherLoad load = TeacherLoad.builder().id(100L).group(group).teacher(teacher).discipline(discipline).build();

        SolverResult.Unplaced unplaced = new SolverResult.Unplaced(100L, 1L, 1L, 1L, 1,
                SolverResult.Reason.GROUP_WEEK_LIMIT, Map.of());
        SolverResult solution = new SolverResult(List.of(), List.of(unplaced), 0, Map.of());

        GenerationSessionStore.Session session = new GenerationSessionStore.Session();
        List<MissingResourceRequest> issues = service.unplacedIssues(solution, Map.of(100L, load), session);
        assertEquals(1, issues.size());
        MissingResourceRequest issue = issues.get(0);
        assertEquals(1L, issue.context().get("groupId"));

        MissingResourceRequest.ResolutionOption raiseOption = issue.options().stream()
                .filter(o -> MissingResourceRequest.Actions.RAISE_GROUP_WEEK_LIMIT.equals(o.actionCode()))
                .findFirst().orElse(null);
        assertNotNull(raiseOption, "должна быть опция поднять недельный лимит группы");

        session.getOpenIssues().put(issue.id(), issue);
        ResolutionDecision decision = new ResolutionDecision(issue.id(),
                MissingResourceRequest.Actions.RAISE_GROUP_WEEK_LIMIT, Map.of("pairsPerWeek", 19));
        service.applyDecision(session, decision);

        assertEquals(19, session.getGroupWeekLimitOverrides().get(1L));

        List<SolverInput.GroupRef> groups = service.buildGroups(List.of(load), SolverConfig.defaults(), session);
        assertEquals(1, groups.size());
        assertEquals(19, groups.get(0).maxWeeklyPairs());
    }

    @Test
    void withoutOverrideGroupKeepsDefaultWeekLimit() {
        StudyGroup group = StudyGroup.builder().id(2L).name("№ 5 Логистика").studentCount(25).build();
        Teacher teacher = Teacher.builder().id(2L).fullName("Анохина А.А.").build();
        Discipline discipline = Discipline.builder().id(2L).name("МДК.03.01.").build();
        TeacherLoad load = TeacherLoad.builder().id(200L).group(group).teacher(teacher).discipline(discipline).build();

        GenerationSessionStore.Session session = new GenerationSessionStore.Session();
        List<SolverInput.GroupRef> groups = service.buildGroups(List.of(load), SolverConfig.defaults(), session);
        assertEquals(18, groups.get(0).maxWeeklyPairs());
    }
}
