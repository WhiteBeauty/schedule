package com.karyakina.schedule.service;

import com.karyakina.schedule.domain.*;
import com.karyakina.schedule.dto.WipeScheduleResultDto;
import com.karyakina.schedule.dto.WipeDatabaseResultDto;
import com.karyakina.schedule.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Безопасное каскадное удаление. Прямой repository.deleteById() на Teacher/Discipline/
 * StudyGroup падает с ошибкой внешнего ключа, если есть связанные TeacherLoad/Schedule/
 * LessonInstance/SubstitutionRequest - этот сервис сначала вручную вычищает всю цепочку
 * зависимостей в правильном порядке (потомки раньше родителей), затем удаляет саму запись.
 *
 * Порядок зависимостей вокруг TeacherLoad:
 *   SubstitutionRequest -> LessonInstance -> Schedule -> TeacherLoad
 *                                                           |-> ControlPoint, MonthlyRecord
 *                                                               (каскадируются автоматически
 *                                                                через JPA orphanRemoval)
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdminDeletionService {

    private final TeacherRepository teacherRepository;
    private final DisciplineRepository disciplineRepository;
    private final StudyGroupRepository groupRepository;
    private final TeacherLoadRepository loadRepository;
    private final ScheduleRepository scheduleRepository;
    private final LessonInstanceRepository lessonInstanceRepository;
    private final SubstitutionRequestRepository substitutionRequestRepository;
    private final SickLeaveRepository sickLeaveRepository;
    private final CuratorshipRepository curatorshipRepository;
    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;
    private final MonthlyRecordService monthlyRecordService;
    private final ClassroomRepository classroomRepository;
    private final AppSettingRepository appSettingRepository;
    private final AuditLogRepository auditLogRepository;
    private final ControlPointRepository controlPointRepository;
    private final MonthlyRecordRepository monthlyRecordRepository;

    @Transactional
    public void deleteTeacherCompletely(Long teacherId) {
        Teacher teacher = teacherRepository.findById(teacherId)
                .orElseThrow(() -> new RuntimeException("Преподаватель не найден: " + teacherId));

        List<SubstitutionRequest> subRequests = substitutionRequestRepository
                .findByOriginalTeacherIdOrCandidateTeacherId(teacherId, teacherId);
        substitutionRequestRepository.deleteAll(subRequests);

        List<LessonInstance> instances = lessonInstanceRepository
                .findByOriginalTeacherIdOrActualTeacherId(teacherId, teacherId);
        deleteInstancesWithSubstitutions(instances);

        List<TeacherLoad> loads = loadRepository.findByTeacherId(teacherId);
        for (TeacherLoad load : loads) {
            deleteTeacherLoadCascade(load);
        }

        sickLeaveRepository.deleteAll(sickLeaveRepository.findByTeacherId(teacherId));
        curatorshipRepository.deleteAll(curatorshipRepository.findByTeacherId(teacherId));
        notificationRepository.deleteAll(notificationRepository.findByRecipientTeacherIdOrderByCreatedAtDesc(teacherId));

        userRepository.findByTeacherId(teacherId).ifPresent(userRepository::delete);

        teacherRepository.delete(teacher);
        log.info("Преподаватель полностью удалён: {} (id={})", teacher.getFullName(), teacherId);
    }

    @Transactional
    public void deleteDisciplineCompletely(Long disciplineId) {
        Discipline discipline = disciplineRepository.findById(disciplineId)
                .orElseThrow(() -> new RuntimeException("Дисциплина не найдена: " + disciplineId));

        List<TeacherLoad> loads = loadRepository.findByDisciplineId(disciplineId);
        notifyAffectedTeachers(loads, "Дисциплина «" + discipline.getName() + "» удалена администратором " +
                "вместе со всеми связанными парами в расписании.");

        for (TeacherLoad load : loads) {
            deleteTeacherLoadCascade(load);
        }

        disciplineRepository.delete(discipline);
        log.info("Дисциплина полностью удалена: {} (id={}), затронуто нагрузок: {}",
                discipline.getName(), disciplineId, loads.size());
    }

    @Transactional
    public void deleteGroupCompletely(Long groupId) {
        StudyGroup group = groupRepository.findById(groupId)
                .orElseThrow(() -> new RuntimeException("Группа не найдена: " + groupId));

        List<TeacherLoad> loads = loadRepository.findByGroupId(groupId);
        notifyAffectedTeachers(loads, "Группа «" + group.getName() + "» удалена администратором " +
                "вместе со всеми связанными парами в расписании.");

        for (TeacherLoad load : loads) {
            deleteTeacherLoadCascade(load);
        }

        curatorshipRepository.deleteAll(curatorshipRepository.findByGroupId(groupId));

        groupRepository.delete(group);
        log.info("Группа полностью удалена: {} (id={}), затронуто нагрузок: {}",
                group.getName(), groupId, loads.size());
    }

    @Transactional
    public WipeScheduleResultDto wipeSchedule(Integer academicYear, DayOfWeek dayOfWeek, Integer academicWeek) {
        List<Schedule> schedules;
        String scope;

        if (dayOfWeek != null && academicWeek != null) {
            schedules = scheduleRepository.findByAcademicYearAndDayOfWeek(academicYear, dayOfWeek).stream()
                    .filter(s -> academicWeek.equals(s.getAcademicWeek()))
                    .collect(Collectors.toList());
            scope = "день " + dayOfWeek + ", неделя " + academicWeek;
        } else if (dayOfWeek != null) {
            schedules = scheduleRepository.findByAcademicYearAndDayOfWeek(academicYear, dayOfWeek);
            scope = "день " + dayOfWeek + " (все недели)";
        } else if (academicWeek != null) {
            schedules = scheduleRepository.findByAcademicYearAndAcademicWeek(academicYear, academicWeek);
            scope = "неделя " + academicWeek + " (все дни)";
        } else {
            schedules = scheduleRepository.findByAcademicYear(academicYear);
            scope = "весь учебный год " + academicYear;
        }

        int count = schedules.size();

        Map<Long, List<Schedule>> byTeacher = new HashMap<>();
        for (Schedule s : schedules) {
            byTeacher.computeIfAbsent(s.getTeacherLoad().getTeacher().getId(), k -> new ArrayList<>()).add(s);
        }
        for (Map.Entry<Long, List<Schedule>> entry : byTeacher.entrySet()) {
            Teacher t = entry.getValue().get(0).getTeacherLoad().getTeacher();
            try {
                notificationService.notifyTeacher(t, Notification.Type.SCHEDULE_CHANGED,
                        "Массовое удаление пар из расписания",
                        "Администратор удалил из вашего расписания пар: " + entry.getValue().size()
                                + " (" + scope + ").",
                        null, "/schedule", false);
            } catch (Exception e) {
                log.warn("Не удалось отправить уведомление преподавателю {}: {}", t.getId(), e.getMessage());
            }
        }

        deleteSchedulesCascade(schedules);

        log.info("Снос расписания: {} ({} пар удалено)", scope, count);
        return WipeScheduleResultDto.builder().deletedPairs(count).scope(scope).build();
    }

    @Transactional
    public void deleteScheduleEntryCompletely(Long scheduleId) {
        Schedule schedule = scheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new RuntimeException("Запись расписания не найдена: " + scheduleId));
        deleteSchedulesCascade(List.of(schedule));
    }

    @Transactional
    public void deleteTeacherLoadCompletely(Long loadId) {
        TeacherLoad load = loadRepository.findById(loadId)
                .orElseThrow(() -> new RuntimeException("Нагрузка не найдена: " + loadId));
        deleteTeacherLoadCascade(load);
    }

    private static final java.util.regex.Pattern MULTI_VALUE_SPLIT = java.util.regex.Pattern.compile("[,;]+");

    private List<String> splitTokens(String raw) {
        List<String> tokens = new ArrayList<>();
        for (String part : MULTI_VALUE_SPLIT.split(raw)) {
            String t = part.trim();
            if (!t.isEmpty()) tokens.add(t);
        }
        return tokens;
    }

    /**
     * ОДНОРАЗОВАЯ ОЧИСТКА уже накопленных "слитных" названий групп/дисциплин — тех,
     * что попали в базу ДО того, как импорт научился разбивать ячейку с несколькими
     * значениями через запятую/`;` (см. ImportService.expandMultiValueRows). Симптом
     * именно в этом, а не в самом алгоритме составления расписания: если у нагрузки
     * группа буквально называется "ИС2-Б23, ИС1-Б23, ИВТ, М, ИСТ" (одно "имя" вместо
     * пяти групп), а дисциплина — "Высшая математика, мат. Анализ" (одно "имя" вместо
     * двух дисциплин), то преподаватель выглядит так, будто ведёт 5 групп и 2 предмета
     * одновременно — на самом деле это одна (кривая) запись нагрузки, generator её
     * размещает без конфликтов, но по смыслу это неверно.
     *
     * Каждую такую нагрузку разбивает на отдельные записи (одна на каждую комбинацию
     * группа×дисциплина), удаляет старые пары расписания по ней (они относятся к
     * "плохой" версии — по новым записям расписание нужно составить заново), затем
     * удаляет саму слитную нагрузку, а если на слитную группу/дисциплину больше никто
     * не ссылается — удаляет и её.
     */
    @Transactional
    public Map<String, Object> splitMergedGroupsAndDisciplines() {
        List<TeacherLoad> allLoads = loadRepository.findAll();
        int loadsSplit = 0, loadsCreated = 0, schedulesRemoved = 0;
        Set<Long> touchedGroupIds = new HashSet<>();
        Set<Long> touchedDisciplineIds = new HashSet<>();

        for (TeacherLoad load : new ArrayList<>(allLoads)) {
            String groupName = load.getGroup().getName();
            String disciplineName = load.getDiscipline().getName();
            List<String> groupTokens = splitTokens(groupName);
            List<String> disciplineTokens = splitTokens(disciplineName);
            if (groupTokens.isEmpty()) groupTokens = List.of(groupName);
            if (disciplineTokens.isEmpty()) disciplineTokens = List.of(disciplineName);
            if (groupTokens.size() <= 1 && disciplineTokens.size() <= 1) continue; // нечего разбивать

            touchedGroupIds.add(load.getGroup().getId());
            touchedDisciplineIds.add(load.getDiscipline().getId());
            schedulesRemoved += scheduleRepository.findByTeacherLoadId(load.getId()).size();

            for (String g : groupTokens) {
                StudyGroup group = groupRepository.findByNameIgnoreCase(g)
                        .orElseGet(() -> groupRepository.save(StudyGroup.builder().name(g).build()));
                for (String d : disciplineTokens) {
                    Discipline discipline = disciplineRepository.findByNameIgnoreCase(d)
                            .orElseGet(() -> disciplineRepository.save(Discipline.builder().name(d).build()));

                    TeacherLoad copy = TeacherLoad.builder()
                            .teacher(load.getTeacher())
                            .candidateTeacherNames(load.getCandidateTeacherNames())
                            .group(group)
                            .discipline(discipline)
                            .plannedHours(load.getPlannedHours())
                            .firstSemesterHours(load.getFirstSemesterHours())
                            .secondSemesterHours(load.getSecondSemesterHours())
                            .readHours(0)
                            .academicYear(load.getAcademicYear())
                            .hoursPerWeek(load.getHoursPerWeek())
                            .lessonType(load.getLessonType())
                            .preferredDays(load.getPreferredDays())
                            .controlPointType1(load.getControlPointType1())
                            .overload(load.getOverload())
                            .build();
                    TeacherLoad saved = loadRepository.save(copy);
                    monthlyRecordService.createMonthlyRecordsForLoad(saved);
                    loadsCreated++;
                }
            }

            try {
                notifyAffectedTeachers(List.of(load),
                        "Исправлена ошибка данных: нагрузка «" + disciplineName + "» / «" + groupName + "» была " +
                        "слитным названием нескольких групп/дисциплин и разбита на отдельные записи. Прежние пары " +
                        "по ней удалены — расписание по новым записям нужно составить заново.");
            } catch (Exception e) {
                log.warn("Не удалось уведомить преподавателя об очистке слитной нагрузки {}: {}", load.getId(), e.getMessage());
            }

            deleteTeacherLoadCascade(load);
            loadsSplit++;
        }

        int groupsDeleted = 0;
        for (Long groupId : touchedGroupIds) {
            StudyGroup group = groupRepository.findById(groupId).orElse(null);
            if (group != null && loadRepository.findByGroupId(groupId).isEmpty()) {
                groupRepository.delete(group);
                groupsDeleted++;
            }
        }
        int disciplinesDeleted = 0;
        for (Long disciplineId : touchedDisciplineIds) {
            Discipline discipline = disciplineRepository.findById(disciplineId).orElse(null);
            if (discipline != null && loadRepository.findByDisciplineId(disciplineId).isEmpty()) {
                disciplineRepository.delete(discipline);
                disciplinesDeleted++;
            }
        }

        log.info("Очистка слитных названий: разобрано нагрузок {}, создано новых {}, удалено пар {}, удалено групп {}, удалено дисциплин {}",
                loadsSplit, loadsCreated, schedulesRemoved, groupsDeleted, disciplinesDeleted);

        Map<String, Object> result = new HashMap<>();
        result.put("loadsSplit", loadsSplit);
        result.put("loadsCreated", loadsCreated);
        result.put("schedulesRemoved", schedulesRemoved);
        result.put("groupsDeleted", groupsDeleted);
        result.put("disciplinesDeleted", disciplinesDeleted);
        return result;
    }

    private void deleteTeacherLoadCascade(TeacherLoad load) {
        List<Schedule> schedules = scheduleRepository.findByTeacherLoadId(load.getId());
        deleteSchedulesCascade(schedules);

        List<LessonInstance> directInstances = lessonInstanceRepository.findByTeacherLoadId(load.getId());
        deleteInstancesWithSubstitutions(directInstances);

        loadRepository.delete(load);
    }

    private void deleteSchedulesCascade(List<Schedule> schedules) {
        if (schedules.isEmpty()) return;
        List<Long> scheduleIds = schedules.stream().map(Schedule::getId).collect(Collectors.toList());
        List<LessonInstance> instances = lessonInstanceRepository.findByScheduleIdIn(scheduleIds);
        deleteInstancesWithSubstitutions(instances);
        scheduleRepository.deleteAll(schedules);
    }

    private void deleteInstancesWithSubstitutions(List<LessonInstance> instances) {
        if (instances.isEmpty()) return;
        List<Long> instanceIds = instances.stream().map(LessonInstance::getId).collect(Collectors.toList());
        substitutionRequestRepository.deleteByLessonInstanceIdIn(instanceIds);
        lessonInstanceRepository.deleteAll(instances);
    }

    private void notifyAffectedTeachers(List<TeacherLoad> loads, String message) {
        Set<Long> notifiedTeacherIds = new HashSet<>();
        for (TeacherLoad load : loads) {
            Teacher t = load.getTeacher();
            if (t == null || !notifiedTeacherIds.add(t.getId())) continue;
            try {
                notificationService.notifyTeacher(t, Notification.Type.LOAD_CHANGED,
                        "Изменение в вашей нагрузке", message, null, "/schedule", false);
            } catch (Exception e) {
                log.warn("Не удалось отправить уведомление преподавателю {}: {}", t.getId(), e.getMessage());
            }
        }
    }

    /**
     * ПОЛНЫЙ СНОС БАЗЫ ДАННЫХ, КРОМЕ ВХОДА АДМИНИСТРАТОРОВ. Только для тестирования —
     * позволяет откатить базу в чистое состояние и заново прогнать импорт/автосоставление,
     * не пересоздавая учётку(и) с ролью ADMIN (чтобы не потерять возможность зайти в систему).
     *
     * Удаляет ВСЁ остальное: преподавателей, дисциплины, группы, нагрузку, расписание
     * (шаблоны и фактические занятия), заявки на замену, больничные, кураторства,
     * уведомления, аудитории, настройки приложения, журнал аудита и пользователей с ролью
     * TEACHER (в т.ч. их логины — по условию сохраняется вход только для ADMIN).
     *
     * Порядок удаления соблюдает цепочку внешних ключей (см. комментарий в начале класса):
     * SubstitutionRequest -> LessonInstance -> Schedule -> TeacherLoad (после явного сноса
     * его дочерних ControlPoint/MonthlyRecord) -> сами преподаватели/группы/дисциплины.
     * Пользователи-преподаватели удаляются ДО удаления Teacher, так как именно User хранит
     * внешний ключ на Teacher (users.teacher_id), а не наоборот.
     *
     * deleteAllInBatch() используется вместо deleteAll(), так как здесь порядок уже вручную
     * гарантирует отсутствие висячих ссылок — это быстрее (один DELETE-запрос на таблицу
     * вместо построчного удаления через persistence context).
     */
    @Transactional
    public WipeDatabaseResultDto wipeAllExceptAdmins() {
        int substitutionRequests = (int) substitutionRequestRepository.count();
        substitutionRequestRepository.deleteAllInBatch();

        int lessonInstances = (int) lessonInstanceRepository.count();
        lessonInstanceRepository.deleteAllInBatch();

        int schedules = (int) scheduleRepository.count();
        scheduleRepository.deleteAllInBatch();

        int controlPoints = (int) controlPointRepository.count();
        controlPointRepository.deleteAllInBatch();

        int monthlyRecords = (int) monthlyRecordRepository.count();
        monthlyRecordRepository.deleteAllInBatch();

        int teacherLoads = (int) loadRepository.count();
        loadRepository.deleteAllInBatch();

        int sickLeaves = (int) sickLeaveRepository.count();
        sickLeaveRepository.deleteAllInBatch();

        int curatorships = (int) curatorshipRepository.count();
        curatorshipRepository.deleteAllInBatch();

        int notifications = (int) notificationRepository.count();
        notificationRepository.deleteAllInBatch();

        // Пользователей-преподавателей удаляем ДО Teacher (users.teacher_id — внешний ключ
        // именно у User). Администраторов не трогаем — в этом весь смысл операции.
        List<User> nonAdminUsers = userRepository.findAll().stream()
                .filter(u -> u.getRole() != User.Role.ADMIN)
                .collect(Collectors.toList());
        int deletedUsers = nonAdminUsers.size();
        userRepository.deleteAll(nonAdminUsers);

        int teachers = (int) teacherRepository.count();
        teacherRepository.deleteAllInBatch();

        int groups = (int) groupRepository.count();
        groupRepository.deleteAllInBatch();

        int disciplines = (int) disciplineRepository.count();
        disciplineRepository.deleteAllInBatch();

        int classrooms = (int) classroomRepository.count();
        classroomRepository.deleteAllInBatch();

        int appSettings = (int) appSettingRepository.count();
        appSettingRepository.deleteAllInBatch();

        int auditLogs = (int) auditLogRepository.count();
        auditLogRepository.deleteAllInBatch();

        int remainingAdmins = (int) userRepository.count();

        log.warn("ПОЛНЫЙ СНОС БД (кроме входа администраторов) выполнен. Осталось учёток-админов: {}",
                remainingAdmins);

        return WipeDatabaseResultDto.builder()
                .deletedTeachers(teachers)
                .deletedDisciplines(disciplines)
                .deletedGroups(groups)
                .deletedTeacherLoads(teacherLoads)
                .deletedSchedules(schedules)
                .deletedLessonInstances(lessonInstances)
                .deletedSubstitutionRequests(substitutionRequests)
                .deletedSickLeaves(sickLeaves)
                .deletedCuratorships(curatorships)
                .deletedNotifications(notifications)
                .deletedNonAdminUsers(deletedUsers)
                .deletedClassrooms(classrooms)
                .deletedControlPoints(controlPoints)
                .deletedMonthlyRecords(monthlyRecords)
                .deletedAppSettings(appSettings)
                .deletedAuditLogs(auditLogs)
                .remainingAdmins(remainingAdmins)
                .build();
    }
}
