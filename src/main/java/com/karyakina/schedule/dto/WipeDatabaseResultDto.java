package com.karyakina.schedule.dto;

import lombok.Builder;
import lombok.Data;

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
    private int remainingAdmins;
}
