package com.karyakina.schedule.dto;

import lombok.Builder;
import lombok.Data;

/**
 * Итог полного сноса базы данных (кроме входа администраторов) — см.
 * {@link com.karyakina.schedule.service.AdminDeletionService#wipeAllExceptAdmins()}.
 * Используется для тестового цикла: снести всё, заново импортировать тарификацию,
 * прогнать автосоставление — без пересоздания учётки администратора.
 */
@Data @Builder
public class WipeDatabaseResultDto {
    private int deletedTeachers;
    private int deletedDisciplines;
    private int deletedGroups;
    private int deletedTeacherLoads;
    private int deletedSchedules;
    private int deletedLessonInstances;
    private int deletedSubstitutionRequests;
    private int deletedSickLeaves;
    private int deletedCuratorships;
    private int deletedNotifications;
    private int deletedNonAdminUsers;
    private int deletedClassrooms;
    private int deletedControlPoints;
    private int deletedMonthlyRecords;
    private int deletedAppSettings;
    private int deletedAuditLogs;
    private int remainingAdmins; // сколько учёток с ролью ADMIN осталось (должны сохраниться все)
}
