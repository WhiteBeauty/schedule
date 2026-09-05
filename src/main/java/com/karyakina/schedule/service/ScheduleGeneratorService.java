package com.karyakina.schedule.service;

import com.karyakina.schedule.domain.Discipline;
import com.karyakina.schedule.domain.Schedule;
import com.karyakina.schedule.domain.StudyGroup;
import com.karyakina.schedule.domain.Teacher;
import com.karyakina.schedule.domain.TeacherLoad;
import com.karyakina.schedule.dto.GenerationRequestDTO;
import com.karyakina.schedule.dto.GenerationResultDTO;
import com.karyakina.schedule.dto.MissingResourceRequest;
import com.karyakina.schedule.dto.PlannedLessonDto;
import com.karyakina.schedule.dto.ResolutionDecision;
import com.karyakina.schedule.dto.ScheduleGenerationResultDto;
import com.karyakina.schedule.repository.ScheduleRepository;
import com.karyakina.schedule.repository.ClassroomRepository;
import com.karyakina.schedule.repository.TeacherLoadRepository;
import com.karyakina.schedule.repository.TeacherRepository;
import com.karyakina.schedule.service.generator.GenerationGrid;
import com.karyakina.schedule.service.generator.OccupancyIndex;
import com.karyakina.schedule.service.generator.ScheduleSolver;
import com.karyakina.schedule.service.generator.SolverConfig;
import com.karyakina.schedule.service.generator.SolverInput;
import com.karyakina.schedule.service.generator.SolverResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.karyakina.schedule.util.AcademicYearUtil;

/**
 * МОДУЛЬ АВТОМАТИЧЕСКОГО СОСТАВЛЕНИЯ РАСПИСАНИЯ.
 *
 * <p>Сценарий 1 — первичная генерация. Сама расстановка вынесена в {@link ScheduleSolver}
 * (рестарты + выталкивание + локальное улучшение), здесь — подготовка данных, проверки
 * и разбор проблем.
 *
 * <p><b>Интерактивный режим.</b> Сервис не бросает исключений наружу и не возвращает пустых
 * ошибок. Всё, чего не хватает или что противоречит друг другу, возвращается в
 * {@link GenerationResultDTO#missingData()} как вопрос с готовыми вариантами действий:
 * расхождение часов между файлом нагрузки и ручным вводом, отсутствие преподавателя,
 * перегруз свыше 36 ч/нед, нехватка слотов и аудиторий, нерасставленные пары.
 * Администратор отвечает (POST {@code /{sessionId}/resolve}), генерация повторяется
 * с учётом ответа, и только потом черновик фиксируется (POST {@code /{sessionId}/apply}).
 *
 * <p>Жёсткие ограничения (никогда не нарушаются): преподаватель/группа/аудитория не заняты
 * дважды в одном слоте; ≤ 36 ч в неделю у преподавателя; лимит пар в день у группы и
 * преподавателя; не более двух одинаковых пар подряд (три и более запрещены); лимит пар
 * одной дисциплины в день; обеденное окно группы. Мягкие: минимум «окон», равномерность
 * по дням, отсутствие одинакового рисунка дней, пожелания преподавателей.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@Slf4j
public class ScheduleGeneratorService {

    private final TeacherLoadRepository loadRepository;
    private final ScheduleRepository scheduleRepository;
    private final ClassroomRepository classroomRepository;
    private final TeacherRepository teacherRepository;
    private final TeacherAssignmentService teacherAssignmentService;
    private final ScheduleChangeNotifier scheduleChangeNotifier;
    private final MonthlyRecordService monthlyRecordService;
    private final SettingsService settingsService;
    private final ScheduleSolver solver;
    private final GenerationSessionStore sessionStore;

    private static final int APPROX_WEEKS_PER_YEAR = 36;
    // Часы в неделю по дисциплине считаются от часов ИМЕННО текущего/выбранного семестра
    // (TeacherLoad.firstSemesterHours/secondSemesterHours), делённых на WEEKS_PER_SEMESTER,
    // а не от суммы часов за год делённой на APPROX_WEEKS_PER_YEAR — см. resolveSemester()/
    // semesterHours() и комментарий в AcademicYearUtil.getCurrentSemester(). Раньше это было
    // не так, из-за чего у преподавателей с разными по семестрам нагрузками (разные группы,
    // разные часы в 1 и 2 семестре) недельная нагрузка считалась как фиктивное "среднее за
    // год" — не соответствующее ни одной реальной неделе — и после независимого округления
    // каждой отдельной нагрузки до целых пар суммарно давало заметно завышенный (или,
    // наоборот, заниженный для другого семестра) результат.
    private static final int WEEKS_PER_SEMESTER = AcademicYearUtil.WEEKS_PER_SEMESTER;
    private static final int DEFAULT_TEACHER_MAX_PAIRS_PER_DAY = 4;
    private static final int DEFAULT_GROUP_MAX_PAIRS_PER_DAY = 5;

    // =================================================================== публичное API

    /** Запуск генерации: создаёт сессию и возвращает черновик вместе с вопросами администратору. */
    @Transactional
    public GenerationResultDTO generateInteractive(GenerationRequestDTO request, String adminName) {
        Integer year = request != null && request.academicYear() != null
                ? request.academicYear()
                : com.karyakina.schedule.util.AcademicYearUtil.getCurrentAcademicYearStart();
        GenerationSessionStore.Session session = sessionStore.create(request, year);
        GenerationResultDTO result = run(session, adminName);
        if (request != null && request.persist() && !result.hasBlocking()) {
            return commit(session.getId(), adminName);
        }
        return result;
    }

    /** Ответы администратора на вопросы: применяем и пересобираем расписание. */
    public GenerationResultDTO applyDecisions(String sessionId, List<ResolutionDecision> decisions, String adminName) {
        GenerationSessionStore.Session session = sessionStore.find(sessionId).orElse(null);
        if (session == null) {
            return GenerationResultDTO.failed(sessionId, MissingResourceRequest.builder(
                            MissingResourceRequest.Code.DATA_ERROR, "Сессия автосоставления устарела")
                    .message("Черновик хранится ограниченное время. Запустите автосоставление заново — "
                            + "все данные нагрузки на месте, потерялись только ответы этой сессии.")
                    .option(MissingResourceRequest.ResolutionOption.of(
                            MissingResourceRequest.Actions.KEEP_AS_IS, "Запустить заново"))
                    .build());
        }
        try {
            for (ResolutionDecision decision : decisions == null ? List.<ResolutionDecision>of() : decisions) {
                applyDecision(session, decision);
            }
        } catch (Exception e) {
            log.error("Не удалось применить решение администратора", e);
            return GenerationResultDTO.failed(session.getId(), MissingResourceRequest.technical(
                    "Решение не применилось: " + describe(e) + ". Остальные ответы сохранены, попробуйте ещё раз."));
        }
        return run(session, adminName);
    }

    /** Фиксация черновика: создаём записи расписания, пересчитываем учёт, шлём уведомления. */
    @Transactional
    public GenerationResultDTO commit(String sessionId, String adminName) {
        GenerationSessionStore.Session session = sessionStore.find(sessionId).orElse(null);
        if (session == null) {
            return GenerationResultDTO.failed(sessionId, MissingResourceRequest.technical(
                    "Сессия автосоставления устарела — запустите генерацию заново."));
        }
        try {
            List<SolverResult.PlacedPair> draft = new ArrayList<>(session.getDraft());
            if (draft.isEmpty()) {
                return run(session, adminName);
            }
            Map<Long, TeacherLoad> loadsById = loadsByIds(draft.stream().map(SolverResult.PlacedPair::loadId).toList());
            List<Schedule> created = new ArrayList<>();

            for (SolverResult.PlacedPair pair : draft) {
                TeacherLoad load = loadsById.get(pair.loadId());
                if (load == null) {
                    continue;
                }
                // Автоподбор преподавателя фиксируем только сейчас: просмотр черновика
                // не должен менять данные (иначе повторный просмотр не идемпотентен).
                if (load.getTeacher() == null || !Long.valueOf(pair.teacherId()).equals(idOf(load.getTeacher()))) {
                    teacherRepository.findById(pair.teacherId()).ifPresent(load::setTeacher);
                    loadRepository.save(load);
                }
                created.add(Schedule.builder()
                        .teacherLoad(load)
                        .dayOfWeek(GenerationGrid.WORK_DAYS[pair.dayIndex()])
                        .startTime(GenerationGrid.start(pair.pairIndex()))
                        .endTime(GenerationGrid.end(pair.pairIndex()))
                        .classroom(pair.room())
                        .academicWeek(null)
                        .academicYear(load.getAcademicYear())
                        .build());
            }
            scheduleRepository.saveAll(created);
            recalculateMonthlyRecords(created, session.getAcademicYear());
            notifyTeachers(created, adminName);
            session.setPersisted(true);
            session.getDraft().clear();

            // Пересчитывать расписание заново не нужно: показываем сохранённые пары и то,
            // что осталось нерешённым по итогам последнего прогона.
            int missingPairs = session.getLastMetrics().getOrDefault("missingPairs", 0);
            GenerationResultDTO.Status status = missingPairs > 0
                    ? GenerationResultDTO.Status.PARTIAL
                    : GenerationResultDTO.Status.OK;
            return new GenerationResultDTO(session.getId(), status, true, toDtos(created),
                    session.getLastIssues(), session.getLastWarnings(), session.getLastMetrics());
        } catch (Exception e) {
            log.error("Не удалось сохранить сгенерированное расписание", e);
            return GenerationResultDTO.failed(session.getId(), MissingResourceRequest.technical(
                    "Расписание не сохранено: " + describe(e) + ". Черновик остался в силе, изменения не применены."));
        }
    }

    /**
     * Старое API (используется кнопками «Автосоставление (черновик)» и «Применить и сохранить»).
     * Сохранено ради совместимости: внутри работает новый алгоритм, а вопросы администратору
     * сворачиваются в понятные строки conflicts/capacityWarnings.
     */
    @Transactional
    public ScheduleGenerationResultDto generate(Integer academicYear, boolean persist, String adminName) {
        GenerationRequestDTO request = GenerationRequestDTO.forYear(academicYear, persist);
        GenerationResultDTO result = generateInteractive(request, adminName);

        List<String> conflicts = new ArrayList<>();
        List<String> capacityWarnings = new ArrayList<>(result.warnings());
        for (MissingResourceRequest issue : result.missingData()) {
            String options = issue.options().isEmpty() ? "" : " Варианты действий: "
                    + String.join("; ", issue.options().stream().map(o -> o.label()).toList()) + ".";
            String line = issue.title() + ". " + issue.message() + options;
            if (issue.severity() == MissingResourceRequest.Severity.BLOCKING) {
                conflicts.add(line);
            } else {
                capacityWarnings.add(line);
            }
        }
        return ScheduleGenerationResultDto.builder()
                .totalLoadsConsidered(result.metrics().getOrDefault("loadsConsidered", 0))
                .placedLessons(result.successSchedule().size())
                .unresolvedLoads(result.metrics().getOrDefault("unresolvedLoads", 0))
                .createdSchedules(List.of()) // черновик отдаётся плоским списком в новом API
                .capacityWarnings(capacityWarnings)
                .conflicts(conflicts)
                .build();
    }

    // =================================================================== конвейер

    private GenerationResultDTO run(GenerationSessionStore.Session session, String adminName) {
        List<MissingResourceRequest> issues = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Map<String, Integer> metrics = new LinkedHashMap<>();
        try {
            Integer year = session.getAcademicYear();
            List<TeacherLoad> allLoads = loadRepository.findByAcademicYear(year);
            if (allLoads.isEmpty()) {
                warnings.add("За " + year + " учебный год нет ни одной записи нагрузки — "
                        + "импортируйте файл нагрузки или добавьте записи вручную.");
                session.getDraft().clear();
                return new GenerationResultDTO(session.getId(), GenerationResultDTO.Status.PARTIAL, false,
                        List.of(), List.of(), warnings, metrics);
            }

            List<Teacher> allTeachers = teacherRepository.findAll();
            TeacherAssignmentService.Resolution assignment = teacherAssignmentService.resolve(allLoads, allTeachers);
            warnings.addAll(assignment.conflicts);

            List<Schedule> existing = scheduleRepository.findByAcademicYear(year);
            Set<Long> loadsWithSchedule = new HashSet<>();
            existing.forEach(s -> {
                if (s.getTeacherLoad() != null && s.getTeacherLoad().getId() != null) {
                    loadsWithSchedule.add(s.getTeacherLoad().getId());
                }
            });

            Set<Long> groupFilter = new HashSet<>(session.getRequest() == null
                    ? List.of() : session.getRequest().groupIds());

            List<TeacherLoad> candidates = allLoads.stream()
                    .filter(l -> l.getId() != null && !loadsWithSchedule.contains(l.getId()))
                    .filter(l -> !session.getSkippedLoads().contains(l.getId()))
                    .filter(l -> l.getGroup() != null && l.getDiscipline() != null)
                    .filter(l -> groupFilter.isEmpty() || groupFilter.contains(l.getGroup().getId()))
                    .toList();
            metrics.put("loadsConsidered", candidates.size());

            SolverConfig config = buildConfig(session);

            detectDuplicates(candidates, session, issues);
            Map<Long, ResolvedHours> hoursByLoad = reconcileHours(candidates, session, config, issues);
            Map<Long, Teacher> teacherByLoad = resolveTeachers(candidates, assignment, session, allTeachers, issues);

            int semester = resolveSemester(session);

            List<SolverInput.Demand> demands = new ArrayList<>();
            Map<Long, TeacherLoad> loadById = new HashMap<>();
            Map<Long, Double> exactWeeklyHoursByTeacher = new HashMap<>();
            for (TeacherLoad load : candidates) {
                Teacher teacher = teacherByLoad.get(load.getId());
                if (teacher == null) {
                    continue; // вопрос уже задан администратору в resolveTeachers
                }
                loadById.put(load.getId(), load);
                ResolvedHours hours = hoursByLoad.getOrDefault(load.getId(),
                        new ResolvedHours(plannedHours(load), false));
                // Утверждённые вручную часы трактуем как годовые (администратор явно
                // подтвердил именно это число) — делим на весь год. Часы из файла нагрузки
                // (обычный случай) трактуем как часы ИМЕННО текущего семестра.
                int weeksBasis = hours.overridden() ? APPROX_WEEKS_PER_YEAR : WEEKS_PER_SEMESTER;
                int hoursForPeriod = hours.overridden() ? hours.annualHours() : semesterHours(load, semester);
                int pairs = weeklyPairs(load, hoursForPeriod, weeksBasis, config, hours.overridden());
                if (pairs <= 0) {
                    if (plannedHours(load) == 0) {
                        warnings.add("Нагрузка «" + describe(load) + "» пропущена: в ней 0 часов.");
                    }
                    // иначе — дисциплина просто не идёт в этом семестре, это норма, не варнинг
                    continue;
                }
                exactWeeklyHoursByTeacher.merge(teacher.getId(),
                        exactWeeklyHours(load, hoursForPeriod, weeksBasis, hours.overridden()), Double::sum);
                demands.add(new SolverInput.Demand(load.getId(), load.getGroup().getId(),
                        load.getDiscipline().getId(), load.getDiscipline().getName(), teacher.getId(),
                        pairs, hours.annualHours(), preferredDayIndexes(load), preferredPairIndexes(load),
                        session.getMaxSameSubjectPerDay()));
            }

            checkTeacherWeeklyLimits(exactWeeklyHoursByTeacher, teacherByLoad, session, config, issues);

            SolverInput input = new SolverInput(
                    buildGroups(candidates, config, session),
                    buildTeachers(teacherByLoad.values(), config, session),
                    GenerationGrid.rooms(classroomRepository.findAll()),
                    demands,
                    config);

            checkGridCapacity(input, loadById, issues, warnings);

            SolverResult solution = solver.solve(input, index -> occupyExisting(index, existing));

            session.getDraft().clear();
            session.getDraft().addAll(solution.placed());

            issues.addAll(unplacedIssues(solution, loadById, session));
            warnings.addAll(remainingHoursWarnings(solution, demands, loadById, config));

            metrics.putAll(solution.metrics());
            metrics.put("unresolvedLoads", solution.unplaced().size());
            metrics.put("missingPairs", solution.missingPairs());

            session.getOpenIssues().clear();
            issues.forEach(issue -> session.getOpenIssues().put(issue.id(), issue));
            session.getLastIssues().clear();
            session.getLastIssues().addAll(issues);
            session.getLastWarnings().clear();
            session.getLastWarnings().addAll(warnings);
            session.getLastMetrics().clear();
            session.getLastMetrics().putAll(metrics);

            GenerationResultDTO.Status status;
            if (issues.stream().anyMatch(i -> i.severity() == MissingResourceRequest.Severity.BLOCKING)) {
                status = GenerationResultDTO.Status.NEEDS_INPUT;
            } else if (!solution.unplaced().isEmpty()) {
                status = GenerationResultDTO.Status.PARTIAL;
            } else {
                status = GenerationResultDTO.Status.OK;
            }
            return new GenerationResultDTO(session.getId(), status, session.isPersisted(),
                    toDtos(solution.placed(), loadById, teacherByLoad), issues, warnings, metrics);

        } catch (Exception e) {
            // Никаких пустых 500-х: причина уходит на экран администратора текстом.
            log.error("Ошибка автосоставления расписания", e);
            return GenerationResultDTO.failed(session.getId(), MissingResourceRequest.technical(
                    "Внутренняя ошибка при расчёте: " + describe(e)
                            + ". Данные не изменены. Проверьте нагрузку за выбранный год и повторите попытку."));
        }
    }

    // =================================================================== проверки данных

    /**
     * РАСХОЖДЕНИЕ ЧАСОВ. Сверяем часы из файла нагрузки (TeacherLoad.plannedHours, как он
     * был импортирован) с ручным вводом администратора. При расхождении не гадаем и не падаем —
     * задаём вопрос с тремя вариантами, ровно как в требованиях.
     */
    private Map<Long, ResolvedHours> reconcileHours(List<TeacherLoad> loads,
                                                    GenerationSessionStore.Session session,
                                                    SolverConfig config,
                                                    List<MissingResourceRequest> issues) {
        Map<String, GenerationRequestDTO.ManualLoadEntry> manualByKey = new HashMap<>();
        Map<Long, GenerationRequestDTO.ManualLoadEntry> manualByLoad = new HashMap<>();
        if (session.getRequest() != null) {
            for (GenerationRequestDTO.ManualLoadEntry entry : session.getRequest().manualLoad()) {
                if (entry.teacherLoadId() != null) {
                    manualByLoad.put(entry.teacherLoadId(), entry);
                }
                if (entry.groupId() != null && entry.disciplineId() != null) {
                    manualByKey.put(entry.groupId() + "|" + entry.disciplineId(), entry);
                }
            }
        }

        int semester = resolveSemester(session);
        Map<Long, ResolvedHours> result = new HashMap<>();
        for (TeacherLoad load : loads) {
            int fileHours = plannedHours(load);
            Integer approved = session.getApprovedHours().get(load.getId());
            if (approved != null) {
                result.put(load.getId(), new ResolvedHours(approved, true));
                continue;
            }

            GenerationRequestDTO.ManualLoadEntry manual = manualByLoad.get(load.getId());
            if (manual == null) {
                manual = manualByKey.get(load.getGroup().getId() + "|" + load.getDiscipline().getId());
            }
            Integer manualHours = manual == null ? null : manualAnnualHours(manual);

            if (manualHours != null && manualHours != fileHours) {
                issues.add(MissingResourceRequest.builder(MissingResourceRequest.Code.HOURS_MISMATCH,
                                "Расхождение по часам: " + describe(load))
                        .severity(MissingResourceRequest.Severity.BLOCKING)
                        .message(String.format(
                                "Для предмета «%s» (%s) расхождение по часам: в файле нагрузки — %d ч, "
                                        + "при ручном вводе указано %d ч. Что утверждаем?",
                                load.getDiscipline().getName(), load.getGroup().getName(), fileHours, manualHours))
                        .context("loadId", load.getId())
                        .context("groupName", load.getGroup().getName())
                        .context("disciplineName", load.getDiscipline().getName())
                        .context("fileHours", fileHours)
                        .context("manualHours", manualHours)
                        .option(MissingResourceRequest.ResolutionOption.of(
                                MissingResourceRequest.Actions.USE_FILE_HOURS,
                                "Утвердить часы из файла (" + fileHours + ")",
                                Map.of("hours", fileHours)))
                        .option(MissingResourceRequest.ResolutionOption.of(
                                MissingResourceRequest.Actions.USE_MANUAL_HOURS,
                                "Утвердить часы из ручного ввода (" + manualHours + ")",
                                Map.of("hours", manualHours)))
                        .option(MissingResourceRequest.ResolutionOption.withInput(
                                MissingResourceRequest.Actions.USE_CUSTOM_HOURS,
                                "Указать своё количество часов",
                                MissingResourceRequest.InputSpec.number("Часов за год", "hours", 0, 2000, fileHours)))
                        .build());
                // До ответа считаем по файлу, чтобы успешная часть расписания всё равно собралась.
                result.put(load.getId(), new ResolvedHours(fileHours, false));
                continue;
            }

            boolean overridden = manualHours != null;
            int hours = overridden ? manualHours : fileHours;
            result.put(load.getId(), new ResolvedHours(hours, overridden));

            // Явно нереалистичные часы: 50 ч/нед на одну дисциплину не влезут ни в какую сетку.
            // Часы из файла — это часы ТЕКУЩЕГО семестра, а не годовая сумма (см. комментарий
            // у WEEKS_PER_SEMESTER); утверждённые вручную часы по-прежнему годовые.
            int weeksBasis = overridden ? APPROX_WEEKS_PER_YEAR : WEEKS_PER_SEMESTER;
            int hoursForPeriod = overridden ? hours : semesterHours(load, semester);
            int pairs = weeklyPairs(load, hoursForPeriod, weeksBasis, config, overridden);
            int maxPairs = GenerationGrid.slotCount();
            if (pairs > config.teacherMaxWeeklyPairs() || pairs > maxPairs) {
                int fitHours = config.pairsToHours(Math.min(config.teacherMaxWeeklyPairs(), maxPairs))
                        * APPROX_WEEKS_PER_YEAR;
                issues.add(MissingResourceRequest.builder(MissingResourceRequest.Code.HOURS_IMPLAUSIBLE,
                                "Слишком много часов: " + describe(load))
                        .severity(MissingResourceRequest.Severity.BLOCKING)
                        .message(String.format(
                                "По записи «%s» (%s) получается %d пар в неделю — это больше, чем есть слотов "
                                        + "в сетке и чем допускает лимит %d ч/нед. Похоже, в часы попало суммарное "
                                        + "или годовое число по нескольким группам.",
                                load.getDiscipline().getName(), load.getGroup().getName(), pairs,
                                config.teacherMaxWeeklyHours()))
                        .context("loadId", load.getId())
                        .context("hours", hours)
                        .option(MissingResourceRequest.ResolutionOption.of(
                                MissingResourceRequest.Actions.REDUCE_HOURS_TO_FIT,
                                "Обрезать до максимума (" + fitHours + " ч/год)",
                                Map.of("hours", fitHours)))
                        .option(MissingResourceRequest.ResolutionOption.withInput(
                                MissingResourceRequest.Actions.USE_CUSTOM_HOURS,
                                "Указать правильное количество часов",
                                MissingResourceRequest.InputSpec.number("Часов за год", "hours", 0, 2000, fitHours)))
                        .option(MissingResourceRequest.ResolutionOption.of(
                                MissingResourceRequest.Actions.SKIP_LOAD,
                                "Пропустить эту нагрузку",
                                Map.of("loadId", load.getId())))
                        .build());
            } else {
                // Отдельная, независимая от лимита преподавателя проверка: сколько часов ОДНОГО
                // предмета выходит в неделю у ОДНОЙ группы. Даже если суммарно у преподавателя
                // всё в пределах нормы (см. checkTeacherWeeklyLimits — та проверка суммирует ВСЕ
                // предметы и группы этого преподавателя), сама по себе цифра "20 ч обществознания
                // в неделю у одной группы" — почти наверняка ошибка в данных, а не физическая
                // реальность. Порог настраиваемый (SolverConfig.maxWeeklyHoursPerSubjectPerGroup,
                // по умолчанию 8 ч/нед = 4 пары) и это только ПРЕДУПРЕЖДЕНИЕ (WARNING) — не
                // блокирует генерацию, так как учебная/производственная практика блоками
                // (см. например "Вождение", "УП.01" в тарификации) законно идёт куда интенсивнее.
                double exactHours = exactWeeklyHours(load, hoursForPeriod, weeksBasis, overridden);
                int limitPerSubject = config.maxWeeklyHoursPerSubjectPerGroup();
                if (exactHours > limitPerSubject) {
                    int roundedHours = (int) Math.round(exactHours);
                    // "Одобренные часы" (session.getApprovedHours()) везде в этом классе трактуются
                    // как ГОДОВЫЕ (см. ResolvedHours/overridden выше) — поэтому подсказка тоже в
                    // годовых часах, даже если сам предел задан в часах/неделю.
                    int fitHoursAnnual = limitPerSubject * APPROX_WEEKS_PER_YEAR;
                    issues.add(MissingResourceRequest.builder(MissingResourceRequest.Code.SUBJECT_GROUP_OVERLOAD,
                                    "Много часов одного предмета у группы: " + describe(load))
                            .severity(MissingResourceRequest.Severity.WARNING)
                            .message(String.format(
                                    "У группы %s по предмету «%s» выходит %d ч/нед — больше настроенного "
                                            + "предела %d ч/нед на один предмет у одной группы (это отдельная "
                                            + "проверка, не связанная с общей недельной нагрузкой преподавателя). "
                                            + "Если это учебная/производственная практика блоками — можно "
                                            + "оставить как есть.",
                                    load.getGroup().getName(), load.getDiscipline().getName(), roundedHours,
                                    limitPerSubject))
                            .context("loadId", load.getId())
                            .context("hours", hours)
                            .option(MissingResourceRequest.ResolutionOption.of(
                                    MissingResourceRequest.Actions.KEEP_AS_IS,
                                    "Оставить как есть (это практика/интенсив)"))
                            .option(MissingResourceRequest.ResolutionOption.of(
                                    MissingResourceRequest.Actions.REDUCE_HOURS_TO_FIT,
                                    "Обрезать до предела (" + fitHoursAnnual + " ч/год)",
                                    Map.of("hours", fitHoursAnnual)))
                            .option(MissingResourceRequest.ResolutionOption.withInput(
                                    MissingResourceRequest.Actions.USE_CUSTOM_HOURS,
                                    "Указать своё количество часов",
                                    MissingResourceRequest.InputSpec.number(
                                            "Часов за год", "hours", 0, 2000, fitHoursAnnual)))
                            .build());
                }
            }
        }
        return result;
    }

    /** Дубликаты «преподаватель + дисциплина + группа»: они конкурируют за одни и те же слоты. */
    private void detectDuplicates(List<TeacherLoad> loads,
                                  GenerationSessionStore.Session session,
                                  List<MissingResourceRequest> issues) {
        Map<String, List<TeacherLoad>> byTriple = new LinkedHashMap<>();
        for (TeacherLoad load : loads) {
            String key = (load.getTeacher() != null ? load.getTeacher().getId() : "auto")
                    + "|" + load.getDiscipline().getId() + "|" + load.getGroup().getId();
            byTriple.computeIfAbsent(key, k -> new ArrayList<>()).add(load);
        }
        for (List<TeacherLoad> group : byTriple.values()) {
            if (group.size() <= 1) {
                continue;
            }
            TeacherLoad first = group.get(0);
            int total = group.stream().mapToInt(this::plannedHours).sum();
            String hoursList = group.stream().map(l -> String.valueOf(plannedHours(l)))
                    .reduce((a, b) -> a + ", " + b).orElse("");
            List<Long> extraIds = group.stream().skip(1).map(TeacherLoad::getId).toList();

            issues.add(MissingResourceRequest.builder(MissingResourceRequest.Code.DUPLICATE_LOAD,
                            "Дубликат нагрузки: " + describe(first))
                    .severity(MissingResourceRequest.Severity.WARNING)
                    .message(String.format(
                            "Одна и та же связка «%s / %s / группа %s» задана %d записями с часами (%s). "
                                    + "Они будут конкурировать за одни и те же слоты.",
                            first.getTeacher() != null ? first.getTeacher().getFullName() : "преподаватель не назначен",
                            first.getDiscipline().getName(), first.getGroup().getName(), group.size(), hoursList))
                    .context("loadId", first.getId())
                    .context("duplicateIds", extraIds)
                    .option(MissingResourceRequest.ResolutionOption.of(
                            MissingResourceRequest.Actions.SUM_DUPLICATES,
                            "Свести в одну запись (" + total + " ч)",
                            Map.of("hours", total, "duplicateIds", extraIds)))
                    .option(MissingResourceRequest.ResolutionOption.of(
                            MissingResourceRequest.Actions.KEEP_FIRST_DUPLICATE,
                            "Оставить первую, остальные пропустить",
                            Map.of("duplicateIds", extraIds)))
                    .option(MissingResourceRequest.ResolutionOption.of(
                            MissingResourceRequest.Actions.KEEP_AS_IS, "Ставить все как есть"))
                    .build());
        }
    }

    /** Преподаватель записи: явный, подобранный автоматически или назначенный администратором. */
    private Map<Long, Teacher> resolveTeachers(List<TeacherLoad> loads,
                                               TeacherAssignmentService.Resolution assignment,
                                               GenerationSessionStore.Session session,
                                               List<Teacher> allTeachers,
                                               List<MissingResourceRequest> issues) {
        Map<Long, Teacher> byId = new HashMap<>();
        allTeachers.forEach(t -> byId.put(t.getId(), t));

        Map<Long, Teacher> result = new HashMap<>();
        for (TeacherLoad load : loads) {
            Long manual = session.getAssignedTeachers().get(load.getId());
            Teacher teacher = manual != null ? byId.get(manual) : null;
            if (teacher == null) {
                teacher = load.getTeacher() != null
                        ? load.getTeacher()
                        : assignment.resolvedTeacherByLoadId.get(load.getId());
            }
            if (teacher != null) {
                result.put(load.getId(), teacher);
                continue;
            }
            List<MissingResourceRequest.Choice> choices = allTeachers.stream()
                    .sorted(Comparator.comparing(t -> matchesDiscipline(t, load) ? 0 : 1))
                    .limit(30)
                    .map(t -> new MissingResourceRequest.Choice(String.valueOf(t.getId()),
                            t.getFullName() + (matchesDiscipline(t, load) ? " — ведёт эту дисциплину" : "")))
                    .toList();

            issues.add(MissingResourceRequest.builder(MissingResourceRequest.Code.NO_TEACHER_FOR_DISCIPLINE,
                            "Не назначен преподаватель: " + describe(load))
                    .severity(MissingResourceRequest.Severity.BLOCKING)
                    .message(String.format(
                            "Для дисциплины «%s» (группа %s) не указан преподаватель, и подобрать его "
                                    + "автоматически не вышло: нет никого с подходящей специализацией "
                                    + "и свободным резервом часов.",
                            load.getDiscipline().getName(), load.getGroup().getName()))
                    .context("loadId", load.getId())
                    .option(MissingResourceRequest.ResolutionOption.withInput(
                            MissingResourceRequest.Actions.ASSIGN_TEACHER,
                            "Назначить преподавателя",
                            MissingResourceRequest.InputSpec.select("Преподаватель", "teacherId", choices)))
                    .option(MissingResourceRequest.ResolutionOption.of(
                            MissingResourceRequest.Actions.SKIP_LOAD,
                            "Пропустить эту нагрузку",
                            Map.of("loadId", load.getId())))
                    .build());
        }
        return result;
    }

    /**
     * Недельная нагрузка преподавателя не должна превышать лимит (по умолчанию 36 ч).
     *
     * <p>Считаем по ТОЧНЫМ (не округлённым до целой пары) часам каждой нагрузки за текущий
     * семестр, а не по сумме уже округлённых до пар часов — иначе независимое округление
     * каждой отдельной нагрузки (группы) вверх/вниз до ближайшей целой пары накапливает
     * ошибку при суммировании по преподавателю с большим числом мелких нагрузок и завышает
     * (или занижает) реальный перегруз.
     */
    private void checkTeacherWeeklyLimits(Map<Long, Double> exactWeeklyHoursByTeacher,
                                          Map<Long, Teacher> teacherByLoad,
                                          GenerationSessionStore.Session session,
                                          SolverConfig config,
                                          List<MissingResourceRequest> issues) {
        for (Map.Entry<Long, Double> entry : exactWeeklyHoursByTeacher.entrySet()) {
            Long teacherId = entry.getKey();
            if (session.getAcknowledgedTeacherOverloads().contains(teacherId)) {
                continue; // администратор уже осознанно принял перегруз — не спрашиваем повторно
            }
            int limitHours = session.getTeacherHourLimits()
                    .getOrDefault(teacherId, config.teacherMaxWeeklyHours());
            int hours = (int) Math.round(entry.getValue());
            if (hours <= limitHours) {
                continue;
            }
            Teacher teacher = teacherByLoad.values().stream()
                    .filter(t -> teacherId.equals(t.getId())).findFirst().orElse(null);
            String name = teacher == null ? "преподаватель #" + teacherId : teacher.getFullName();

            issues.add(MissingResourceRequest.builder(MissingResourceRequest.Code.TEACHER_OVERLOAD,
                            "Перегруз преподавателя: " + name)
                    .severity(MissingResourceRequest.Severity.BLOCKING)
                    .message(String.format(
                            "По плану у преподавателя %s выходит %d ч в неделю при лимите %d ч. "
                                    + "Часть пар физически не встанет.",
                            name, hours, limitHours))
                    .context("teacherId", teacherId)
                    .context("hours", hours)
                    .option(MissingResourceRequest.ResolutionOption.of(
                            MissingResourceRequest.Actions.REDUCE_HOURS_TO_FIT,
                            "Ставить в пределах лимита, остаток оставить нераспределённым"))
                    .option(MissingResourceRequest.ResolutionOption.withInput(
                            MissingResourceRequest.Actions.RAISE_TEACHER_LIMIT,
                            "Поднять лимит для этого преподавателя",
                            MissingResourceRequest.InputSpec.number("Часов в неделю", "hours",
                                    limitHours, 60, hours)))
                    .build());
        }
    }

    /** Хватит ли в сетке слотов группе на всю её недельную нагрузку. */
    private void checkGridCapacity(SolverInput input,
                                   Map<Long, TeacherLoad> loadById,
                                   List<MissingResourceRequest> issues,
                                   List<String> warnings) {
        Map<Long, Integer> pairsByGroup = new HashMap<>();
        input.demands().forEach(d -> pairsByGroup.merge(d.groupId(), d.pairsPerWeek(), Integer::sum));

        for (SolverInput.GroupRef group : input.groups()) {
            int needed = pairsByGroup.getOrDefault(group.id(), 0);
            if (needed == 0) {
                continue;
            }
            int available = Math.min(
                    GenerationGrid.slotCount() - group.blockedSlots().size(),
                    group.maxPairsPerDay() * GenerationGrid.days());
            if (needed <= available) {
                continue;
            }
            int suggested = Math.min(GenerationGrid.pairsPerDay(),
                    (int) Math.ceil(needed / (double) GenerationGrid.days()));
            issues.add(MissingResourceRequest.builder(MissingResourceRequest.Code.GRID_CAPACITY,
                            "Не хватает слотов: группа " + group.name())
                    .severity(MissingResourceRequest.Severity.BLOCKING)
                    .message(String.format(
                            "Группе %s по плану нужно %d пар в неделю, а в сетке для неё доступно максимум %d "
                                    + "(с учётом обеда и лимита %d пар в день).",
                            group.name(), needed, available, group.maxPairsPerDay()))
                    .context("groupId", group.id())
                    .option(MissingResourceRequest.ResolutionOption.of(
                            MissingResourceRequest.Actions.INCREASE_PAIRS_PER_DAY,
                            "Разрешить до " + suggested + " пар в день",
                            Map.of("pairsPerDay", suggested)))
                    .option(MissingResourceRequest.ResolutionOption.withInput(
                            MissingResourceRequest.Actions.INCREASE_PAIRS_PER_DAY,
                            "Задать лимит пар в день вручную",
                            MissingResourceRequest.InputSpec.number("Пар в день", "pairsPerDay",
                                    1, GenerationGrid.pairsPerDay(), suggested)))
                    .option(MissingResourceRequest.ResolutionOption.of(
                            MissingResourceRequest.Actions.KEEP_AS_IS,
                            "Оставить как есть — лишнее не ставить"))
                    .build());
        }
    }

    /** Нерасставленные пары: один понятный вопрос на нагрузку, с диагнозом и вариантами. */
    private List<MissingResourceRequest> unplacedIssues(SolverResult solution,
                                                        Map<Long, TeacherLoad> loadById,
                                                        GenerationSessionStore.Session session) {
        List<MissingResourceRequest> result = new ArrayList<>();
        for (SolverResult.Unplaced unplaced : solution.unplaced()) {
            TeacherLoad load = loadById.get(unplaced.loadId());
            if (load == null) {
                continue;
            }
            MissingResourceRequest.Builder builder = MissingResourceRequest.builder(
                            MissingResourceRequest.Code.UNPLACED_LESSONS, "Не встали пары: " + describe(load))
                    .severity(MissingResourceRequest.Severity.WARNING)
                    .message(String.format("Не удалось поставить %d пар(ы) «%s» у группы %s — %s.",
                            unplaced.missingPairs(), load.getDiscipline().getName(),
                            load.getGroup().getName(), unplaced.reason().ru()))
                    .context("loadId", load.getId())
                    .context("missingPairs", unplaced.missingPairs())
                    .context("reason", unplaced.reason().name());

            switch (unplaced.reason()) {
                case SUBJECT_LIMIT -> builder.option(MissingResourceRequest.ResolutionOption.withInput(
                        MissingResourceRequest.Actions.RELAX_SUBJECT_PER_DAY,
                        "Разрешить больше пар этой дисциплины в день",
                        MissingResourceRequest.InputSpec.number("Пар в день", "perDay", 1,
                                GenerationGrid.pairsPerDay(), 3)));
                case GROUP_DAY_LIMIT, GROUP_BUSY, LUNCH -> builder.option(
                        MissingResourceRequest.ResolutionOption.withInput(
                                MissingResourceRequest.Actions.INCREASE_PAIRS_PER_DAY,
                                "Разрешить группе больше пар в день",
                                MissingResourceRequest.InputSpec.number("Пар в день", "pairsPerDay", 1,
                                        GenerationGrid.pairsPerDay(), GenerationGrid.pairsPerDay())));
                case TEACHER_WEEK_LIMIT, TEACHER_DAY_LIMIT, TEACHER_BUSY -> builder.option(
                        MissingResourceRequest.ResolutionOption.withInput(
                                MissingResourceRequest.Actions.RAISE_TEACHER_LIMIT,
                                "Поднять недельный лимит преподавателя",
                                MissingResourceRequest.InputSpec.number("Часов в неделю", "hours", 36, 60, 40)));
                default -> builder.option(MissingResourceRequest.ResolutionOption.withInput(
                        MissingResourceRequest.Actions.USE_CUSTOM_HOURS,
                        "Уменьшить часы этой дисциплины",
                        MissingResourceRequest.InputSpec.number("Часов за год", "hours", 0, 2000,
                                Math.max(0, plannedHours(load) - unplaced.missingPairs() * 2 * APPROX_WEEKS_PER_YEAR))));
            }
            builder.option(MissingResourceRequest.ResolutionOption.of(
                    MissingResourceRequest.Actions.KEEP_AS_IS, "Оставить нераспределённым"));
            result.add(builder.build());
        }
        return result;
    }

    /** «У преподавателя Иванов И.И. осталось 2 нераспределённых часа нагрузки». */
    private List<String> remainingHoursWarnings(SolverResult solution,
                                                List<SolverInput.Demand> demands,
                                                Map<Long, TeacherLoad> loadById,
                                                SolverConfig config) {
        Map<Long, Integer> placedByLoad = new HashMap<>();
        solution.placed().forEach(p -> placedByLoad.merge(p.loadId(), 1, Integer::sum));

        Map<String, Integer> remainingByTeacher = new LinkedHashMap<>();
        for (SolverInput.Demand demand : demands) {
            int placed = placedByLoad.getOrDefault(demand.loadId(), 0);
            int missing = Math.max(0, demand.pairsPerWeek() - placed);
            if (missing == 0) {
                continue;
            }
            TeacherLoad load = loadById.get(demand.loadId());
            String name = load != null && load.getTeacher() != null
                    ? load.getTeacher().getFullName() : "преподаватель #" + demand.teacherId();
            remainingByTeacher.merge(name, config.pairsToHours(missing), Integer::sum);
        }
        return remainingByTeacher.entrySet().stream()
                .map(e -> "У преподавателя " + e.getKey() + " осталось " + e.getValue()
                        + " нераспределённых часов нагрузки в неделю")
                .toList();
    }

    // =================================================================== применение решений

    private void applyDecision(GenerationSessionStore.Session session, ResolutionDecision decision) {
        if (decision == null || decision.actionCode() == null) {
            return;
        }
        MissingResourceRequest issue = session.getOpenIssues().get(decision.requestId());
        Long loadId = issue == null ? null : asLong(issue.context().get("loadId"));
        Long teacherId = issue == null ? null : asLong(issue.context().get("teacherId"));

        switch (decision.actionCode()) {
            case MissingResourceRequest.Actions.USE_FILE_HOURS,
                 MissingResourceRequest.Actions.USE_MANUAL_HOURS,
                 MissingResourceRequest.Actions.USE_CUSTOM_HOURS,
                 MissingResourceRequest.Actions.REDUCE_HOURS_TO_FIT -> {
                if (loadId != null) {
                    int hours = decision.intValue("hours", -1);
                    if (hours < 0 && issue != null) {
                        hours = asInt(issue.context().get("fileHours"), 0);
                    }
                    if (hours >= 0) {
                        session.getApprovedHours().put(loadId, hours);
                    }
                } else if (teacherId != null) {
                    // TEACHER_OVERLOAD, «Ставить в пределах лимита, остаток — в предупреждения».
                    // Раньше здесь только снимался лимит, которого и не было (no-op) — из-за
                    // этого один и тот же блокирующий вопрос возникал заново на каждом прогоне
                    // и сохранить расписание было невозможно, что бы ни выбрал администратор.
                    // Теперь запоминаем осознанное решение: при следующем прогоне
                    // checkTeacherWeeklyLimits для этого преподавателя вопрос не поднимает,
                    // а то, что часть часов не влезает, по-прежнему видно в предупреждениях
                    // (см. remainingHoursWarnings).
                    session.getAcknowledgedTeacherOverloads().add(teacherId);
                }
            }
            case MissingResourceRequest.Actions.SUM_DUPLICATES -> {
                if (loadId != null) {
                    session.getApprovedHours().put(loadId, decision.intValue("hours", 0));
                }
                skipAll(session, decision, issue);
            }
            case MissingResourceRequest.Actions.KEEP_FIRST_DUPLICATE -> skipAll(session, decision, issue);
            case MissingResourceRequest.Actions.ASSIGN_TEACHER -> {
                Long chosen = decision.longValue("teacherId");
                if (loadId != null && chosen != null) {
                    session.getAssignedTeachers().put(loadId, chosen);
                }
            }
            case MissingResourceRequest.Actions.SKIP_LOAD -> {
                Long target = decision.longValue("loadId");
                if (target == null) {
                    target = loadId;
                }
                if (target != null) {
                    session.getSkippedLoads().add(target);
                }
            }
            case MissingResourceRequest.Actions.RAISE_TEACHER_LIMIT -> {
                int hours = decision.intValue("hours", 0);
                if (teacherId != null && hours > 0) {
                    session.getTeacherHourLimits().put(teacherId, hours);
                } else if (hours > 0) {
                    session.setTeacherMaxWeeklyHours(hours);
                }
            }
            case MissingResourceRequest.Actions.INCREASE_PAIRS_PER_DAY -> {
                int pairsPerDay = decision.intValue("pairsPerDay", 0);
                if (pairsPerDay > 0) {
                    session.setMaxPairsPerDayGroup(pairsPerDay);
                }
            }
            case MissingResourceRequest.Actions.RELAX_SUBJECT_PER_DAY -> {
                int perDay = decision.intValue("perDay", 0);
                if (perDay > 0) {
                    session.setMaxSameSubjectPerDay(perDay);
                }
            }
            default -> {
                // KEEP_AS_IS и незнакомые коды: ничего не меняем, вопрос просто закрывается.
            }
        }
        if (issue != null) {
            session.getOpenIssues().remove(issue.id());
        }
    }

    private void skipAll(GenerationSessionStore.Session session, ResolutionDecision decision,
                         MissingResourceRequest issue) {
        Object ids = decision.payload().get("duplicateIds");
        if (ids == null && issue != null) {
            ids = issue.context().get("duplicateIds");
        }
        if (ids instanceof List<?> list) {
            list.forEach(value -> {
                Long id = asLong(value);
                if (id != null) {
                    session.getSkippedLoads().add(id);
                }
            });
        }
    }

    // =================================================================== построение входа солвера

    private SolverConfig buildConfig(GenerationSessionStore.Session session) {
        SolverConfig config = SolverConfig.defaults();
        GenerationRequestDTO.GridSettings grid = session.getRequest() == null ? null : session.getRequest().grid();
        Integer maxPairsPerDayGroup = session.getMaxPairsPerDayGroup() != null
                ? session.getMaxPairsPerDayGroup()
                : (grid == null ? DEFAULT_GROUP_MAX_PAIRS_PER_DAY : grid.maxPairsPerDayGroup());
        return config.with(
                maxPairsPerDayGroup == null ? DEFAULT_GROUP_MAX_PAIRS_PER_DAY : maxPairsPerDayGroup,
                session.getTeacherMaxWeeklyHours() != null
                        ? session.getTeacherMaxWeeklyHours()
                        : (grid == null ? null : grid.teacherMaxWeeklyHours()),
                grid == null ? null : grid.maxSameSubjectInRow(),
                session.getMaxSameSubjectPerDay() != null
                        ? session.getMaxSameSubjectPerDay()
                        : (grid == null ? null : grid.maxSameSubjectPerDay()),
                grid == null ? null : grid.restarts(),
                grid == null ? null : grid.maxWeeklyHoursPerSubjectPerGroup());
    }

    private List<SolverInput.GroupRef> buildGroups(List<TeacherLoad> loads, SolverConfig config,
                                                   GenerationSessionStore.Session session) {
        Map<Long, StudyGroup> groups = new LinkedHashMap<>();
        loads.forEach(l -> groups.putIfAbsent(l.getGroup().getId(), l.getGroup()));

        List<SolverInput.GroupRef> result = new ArrayList<>();
        for (StudyGroup group : groups.values()) {
            LocalTime[] lunch = effectiveLunchWindow(group);
            Set<Integer> blocked = new HashSet<>();
            if (lunch != null) {
                for (int day = 0; day < GenerationGrid.days(); day++) {
                    for (int pair = 0; pair < GenerationGrid.pairsPerDay(); pair++) {
                        if (GenerationGrid.overlapsLunch(pair, lunch[0], lunch[1])) {
                            blocked.add(GenerationGrid.flat(day, pair));
                        }
                    }
                }
            }
            result.add(new SolverInput.GroupRef(group.getId(), group.getName(),
                    group.getStudentCount() == null ? 0 : group.getStudentCount(),
                    config.maxPairsPerDayGroup(), blocked));
        }
        return result;
    }

    private List<SolverInput.TeacherRef> buildTeachers(java.util.Collection<Teacher> teachers, SolverConfig config,
                                                       GenerationSessionStore.Session session) {
        Map<Long, Teacher> unique = new LinkedHashMap<>();
        teachers.forEach(t -> unique.putIfAbsent(t.getId(), t));

        List<SolverInput.TeacherRef> result = new ArrayList<>();
        for (Teacher teacher : unique.values()) {
            int limitHours = session.getTeacherHourLimits()
                    .getOrDefault(teacher.getId(), config.teacherMaxWeeklyHours());
            int maxWeeklyPairs = Math.max(1, limitHours / config.academicHoursPerPair());
            int maxPerDay = teacher.getMaxPairsPerDay() != null && teacher.getMaxPairsPerDay() > 0
                    ? teacher.getMaxPairsPerDay() : DEFAULT_TEACHER_MAX_PAIRS_PER_DAY;
            result.add(new SolverInput.TeacherRef(teacher.getId(), teacher.getFullName(),
                    maxPerDay, maxWeeklyPairs, Set.of()));
        }
        return result;
    }

    /**
     * Уже сохранённые пары занимают слоты — новые к ним не встанут поверх.
     *
     * <p>Пары с нестандартным временем (добавленные вручную, не по сетке звонков) не
     * игнорируются: занятыми помечаются все слоты, которые пересекаются с их интервалом.
     * Иначе генератор поставил бы поверх них вторую пару той же группе или в ту же аудиторию.
     */
    private void occupyExisting(OccupancyIndex index, List<Schedule> existing) {
        for (Schedule schedule : existing) {
            TeacherLoad load = schedule.getTeacherLoad();
            if (load == null) {
                continue;
            }
            int dayIdx = GenerationGrid.dayIndex(schedule.getDayOfWeek());
            if (dayIdx < 0) {
                continue; // воскресенье или день вне рабочей недели
            }
            Long groupId = load.getGroup() == null ? null : load.getGroup().getId();
            Long teacherId = load.getTeacher() == null ? null : load.getTeacher().getId();
            Long disciplineId = load.getDiscipline() == null ? null : load.getDiscipline().getId();

            for (int pairIdx : occupiedPairIndexes(schedule)) {
                int flat = GenerationGrid.flat(dayIdx, pairIdx);
                index.occupyExternal(groupId, teacherId, schedule.getClassroom(), flat);
                index.occupyExternalContent(groupId, teacherId, disciplineId, flat);
            }
        }
    }

    /** Номера пар, которые перекрывает существующая запись расписания. */
    private List<Integer> occupiedPairIndexes(Schedule schedule) {
        int exact = GenerationGrid.pairIndex(schedule.getStartTime());
        if (exact >= 0) {
            return List.of(exact);
        }
        LocalTime start = schedule.getStartTime();
        LocalTime end = schedule.getEndTime();
        if (start == null || end == null) {
            return List.of();
        }
        List<Integer> result = new ArrayList<>();
        for (int pairIdx = 0; pairIdx < GenerationGrid.pairsPerDay(); pairIdx++) {
            if (start.isBefore(GenerationGrid.end(pairIdx)) && GenerationGrid.start(pairIdx).isBefore(end)) {
                result.add(pairIdx);
            }
        }
        return result;
    }

    // =================================================================== вспомогательное

    private void recalculateMonthlyRecords(List<Schedule> created, Integer academicYear) {
        Map<Long, List<Schedule>> byLoad = new HashMap<>();
        try {
            scheduleRepository.findByAcademicYear(academicYear).forEach(s -> {
                if (s.getTeacherLoad() != null) {
                    byLoad.computeIfAbsent(s.getTeacherLoad().getId(), k -> new ArrayList<>()).add(s);
                }
            });
        } catch (Exception e) {
            log.warn("Не удалось перечитать расписание для помесячного учёта: {}", e.getMessage());
        }
        Set<Long> touched = new LinkedHashSet<>();
        created.forEach(s -> touched.add(s.getTeacherLoad().getId()));
        for (Long loadId : touched) {
            try {
                loadRepository.findById(loadId).ifPresent(load ->
                        monthlyRecordService.recalculateHoursForLoad(load, byLoad.getOrDefault(loadId, List.of())));
            } catch (Exception e) {
                log.warn("Не удалось пересчитать помесячный учёт для нагрузки {}: {}", loadId, e.getMessage());
            }
        }
    }

    private void notifyTeachers(List<Schedule> created, String adminName) {
        if (adminName == null) {
            return;
        }
        for (Schedule schedule : created) {
            try {
                scheduleChangeNotifier.pairCreated(schedule, adminName);
            } catch (Exception e) {
                log.warn("Не удалось отправить уведомление о новой паре: {}", e.getMessage());
            }
        }
    }

    private Map<Long, TeacherLoad> loadsByIds(List<Long> ids) {
        Map<Long, TeacherLoad> result = new HashMap<>();
        for (Long id : new LinkedHashSet<>(ids)) {
            loadRepository.findById(id).ifPresent(load -> result.put(id, load));
        }
        return result;
    }

    private List<PlannedLessonDto> toDtos(List<SolverResult.PlacedPair> placed,
                                          Map<Long, TeacherLoad> loadById,
                                          Map<Long, Teacher> teacherByLoad) {
        List<PlannedLessonDto> result = new ArrayList<>(placed.size());
        for (SolverResult.PlacedPair pair : placed) {
            TeacherLoad load = loadById.get(pair.loadId());
            Teacher teacher = teacherByLoad.get(pair.loadId());
            StudyGroup group = load == null ? null : load.getGroup();
            Discipline discipline = load == null ? null : load.getDiscipline();
            result.add(new PlannedLessonDto(
                    null,
                    pair.loadId(),
                    group == null ? null : group.getId(),
                    group == null ? "—" : group.getName(),
                    discipline == null ? null : discipline.getId(),
                    discipline == null ? "—" : discipline.getName(),
                    teacher == null ? null : teacher.getId(),
                    teacher == null ? "не назначен" : teacher.getFullName(),
                    pair.room(),
                    GenerationGrid.WORK_DAYS[pair.dayIndex()],
                    GenerationGrid.dayName(pair.dayIndex()),
                    pair.pairIndex() + 1,
                    GenerationGrid.start(pair.pairIndex()),
                    GenerationGrid.end(pair.pairIndex())));
        }
        return result;
    }

    private List<PlannedLessonDto> toDtos(List<Schedule> schedules) {
        List<PlannedLessonDto> result = new ArrayList<>(schedules.size());
        for (Schedule schedule : schedules) {
            int pairIdx = GenerationGrid.pairIndex(schedule.getStartTime());
            int dayIdx = GenerationGrid.dayIndex(schedule.getDayOfWeek());
            result.add(PlannedLessonDto.from(schedule, pairIdx + 1, GenerationGrid.dayName(dayIdx)));
        }
        return result;
    }

    private LocalTime[] effectiveLunchWindow(StudyGroup group) {
        if (group.getLunchStart() != null && group.getLunchEnd() != null) {
            return new LocalTime[]{group.getLunchStart(), group.getLunchEnd()};
        }
        try {
            return settingsService.getGlobalLunchWindow();
        } catch (Exception e) {
            return null;
        }
    }

    private int plannedHours(TeacherLoad load) {
        return load.getPlannedHours() == null ? 0 : Math.max(0, load.getPlannedHours());
    }

    private Integer manualAnnualHours(GenerationRequestDTO.ManualLoadEntry entry) {
        if (entry.academicHours() != null && entry.academicHours() >= 0) {
            return entry.academicHours();
        }
        if (entry.hoursPerWeek() != null && entry.hoursPerWeek() >= 0) {
            return entry.hoursPerWeek() * APPROX_WEEKS_PER_YEAR;
        }
        return null;
    }

    /** Утверждённые часы: {@code overridden} = администратор или ручной ввод перебили файл. */
    private record ResolvedHours(int annualHours, boolean overridden) {
    }

    /**
     * Какой семестр (1 или 2) считать "текущим" для расчёта часов в неделю: явно указанный
     * в запросе на генерацию или, если не указан, определяемый по сегодняшней дате.
     */
    private int resolveSemester(GenerationSessionStore.Session session) {
        Integer requested = session.getRequest() == null ? null : session.getRequest().semester();
        if (requested != null && requested == 2) return 2;
        if (requested != null) return 1;
        return AcademicYearUtil.getCurrentSemester();
    }

    /** Часы ИМЕННО указанного семестра (а не сумма за год) — 0, если в этом семестре дисциплина не идёт. */
    private int semesterHours(TeacherLoad load, int semester) {
        Integer h = semester == 2 ? load.getSecondSemesterHours() : load.getFirstSemesterHours();
        return h == null ? 0 : Math.max(0, h);
    }

    /**
     * Точные (не округлённые до целой пары) часы в неделю — используется как для расчёта
     * числа пар, так и отдельно для проверки недельного лимита преподавателя, чтобы
     * независимое округление каждой отдельной нагрузки не накапливало ошибку при суммировании
     * (пример: пять нагрузок по 1.4 пары каждая — это 7 пар/14 ч суммарно, а не 5×2=10 пар,
     * если округлять каждую по отдельности до ближайшей целой пары).
     */
    private double exactWeeklyHours(TeacherLoad load, int hoursForPeriod, int weeksBasis, boolean overridden) {
        if (!overridden && load.getHoursPerWeek() != null && load.getHoursPerWeek() > 0) {
            return load.getHoursPerWeek();
        }
        return hoursForPeriod / (double) weeksBasis;
    }

    /**
     * Часы -> пары в неделю.
     *
     * <p>Обычно приоритет у явного {@code hoursPerWeek} из файла нагрузки. Но если часы
     * утвердил администратор (ответ на расхождение) или они пришли ручным вводом, то считаем
     * именно от них: иначе ответ администратора не влиял бы ни на что, пока в записи
     * заполнено поле «часов в неделю».
     *
     * <p>{@code hoursForPeriod}/{@code weeksBasis} — часы и число недель ЗА ОДИН И ТОТ ЖЕ
     * период: для обычной (не утверждённой вручную) нагрузки это часы текущего семестра и
     * {@link #WEEKS_PER_SEMESTER}, для утверждённой вручную — годовые часы и
     * {@link #APPROX_WEEKS_PER_YEAR} (см. вызывающий код).
     */
    private int weeklyPairs(TeacherLoad load, int hoursForPeriod, int weeksBasis, SolverConfig config,
                             boolean overridden) {
        double hoursPerWeek = exactWeeklyHours(load, hoursForPeriod, weeksBasis, overridden);
        int pairs = (int) Math.round(hoursPerWeek / config.academicHoursPerPair());
        if (pairs <= 0) {
            // 0 часов -> 0 пар. Раньше здесь стояло Math.max(1, ...), из-за чего пустая
            // строка нагрузки всё равно порождала пару в расписании.
            return hoursForPeriod > 0 ? 1 : 0;
        }
        return Math.min(pairs, GenerationGrid.slotCount());
    }

    private boolean matchesDiscipline(Teacher teacher, TeacherLoad load) {
        try {
            return TeacherAssignmentService.matchesSpecialization(teacher, load.getDiscipline().getName());
        } catch (Exception e) {
            return false;
        }
    }

    private String describe(TeacherLoad load) {
        return load.getDiscipline().getName() + " / " + load.getGroup().getName();
    }

    private String describe(Exception e) {
        return e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
    }

    private Long idOf(Teacher teacher) {
        return teacher == null ? null : teacher.getId();
    }

    private Long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private int asInt(Object value, int fallback) {
        Long parsed = asLong(value);
        return parsed == null ? fallback : parsed.intValue();
    }

    // ------------------------------------------------------------------ пожелания из импорта

    /**
     * Ячейка «Предпочтительные дни» из файла нагрузки — свободный текст на русском
     * («Пн, Ср», «понедельник, среда, до 12:00»), поэтому разбираем терпимо.
     */
    private Set<Integer> preferredDayIndexes(TeacherLoad load) {
        if (load.getPreferredDays() == null || load.getPreferredDays().isBlank()) {
            return Set.of();
        }
        Set<Integer> result = new LinkedHashSet<>();
        for (String token : load.getPreferredDays().split("[,;]")) {
            DayOfWeek day = parseDay(token);
            if (day == null) {
                continue;
            }
            int idx = GenerationGrid.dayIndex(day);
            if (idx >= 0) {
                result.add(idx);
            }
        }
        return result;
    }

    private Set<Integer> preferredPairIndexes(TeacherLoad load) {
        if (load.getPreferredTimeSlots() == null || load.getPreferredTimeSlots().isBlank()) {
            return Set.of();
        }
        Set<Integer> result = new LinkedHashSet<>();
        for (String token : load.getPreferredTimeSlots().split("[,;]")) {
            try {
                int number = Integer.parseInt(token.trim());
                if (number >= 1 && number <= GenerationGrid.pairsPerDay()) {
                    result.add(number - 1);
                }
            } catch (NumberFormatException ignored) {
                // мусор в ячейке пожеланий не должен ломать генерацию
            }
        }
        return result;
    }

    private DayOfWeek parseDay(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        Matcher matcher = Pattern.compile("^[А-Яа-яЁёA-Za-z]+").matcher(token.trim());
        if (!matcher.find()) {
            return null;
        }
        String word = matcher.group().toUpperCase(Locale.ROOT).replace("Ё", "Е");
        return switch (word) {
            case "ПН", "ПОНЕДЕЛЬНИК" -> DayOfWeek.MONDAY;
            case "ВТ", "ВТОРНИК" -> DayOfWeek.TUESDAY;
            case "СР", "СРЕДА" -> DayOfWeek.WEDNESDAY;
            case "ЧТ", "ЧЕТВЕРГ" -> DayOfWeek.THURSDAY;
            case "ПТ", "ПЯТНИЦА" -> DayOfWeek.FRIDAY;
            case "СБ", "СУББОТА" -> DayOfWeek.SATURDAY;
            case "ВС", "ВОСКРЕСЕНЬЕ" -> DayOfWeek.SUNDAY;
            default -> {
                try {
                    yield DayOfWeek.valueOf(word);
                } catch (IllegalArgumentException e) {
                    yield null;
                }
            }
        };
    }
}
