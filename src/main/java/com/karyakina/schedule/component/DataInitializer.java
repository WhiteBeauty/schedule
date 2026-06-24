package com.karyakina.schedule.component;

import com.karyakina.schedule.domain.*;
import com.karyakina.schedule.repository.*;
import com.karyakina.schedule.service.MonthlyRecordService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class DataInitializer implements CommandLineRunner {

    private final MonthlyRecordService monthlyRecordService;
    private final TeacherRepository teacherRepository;
    private final StudyGroupRepository groupRepository;
    private final DisciplineRepository disciplineRepository;
    private final TeacherLoadRepository loadRepository;
    private final ScheduleRepository scheduleRepository;
    private final MonthlyRecordRepository monthlyRecordRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(String... args) {
        log.info("Initializing application data...");

        // Проверяем, есть ли уже данные
        if (teacherRepository.count() > 0) {
            log.info("Data already exists, skipping initialization");
            return;
        }

        // Создаём админа
        User admin = User.builder()
                .username("admin")
                .email("admin@example.com")
                .password(passwordEncoder.encode("admin123"))
                .role(User.Role.ADMIN)
                .firstName("Админ")
                .lastName("Системы")
                .build();
        userRepository.save(admin);

        // Создаём преподавателей
        Teacher teacher1 = Teacher.builder()
                .fullName("Иванов Иван Иванович")
                .department("Математика")
                .position("Доцент")
                .rate(36.0)
                .phone("+7-900-111-11-11")
                .build();
        teacher1 = teacherRepository.save(teacher1);

        User user1 = User.builder()
                .username("ivanov")
                .email("ivanov@example.com")
                .password(passwordEncoder.encode("teacher123"))
                .role(User.Role.TEACHER)
                .teacher(teacher1)
                .firstName("Иван")
                .lastName("Иванов")
                .phone("+7-900-111-11-11")
                .build();
        userRepository.save(user1);
        teacher1.setUser(user1);
        teacherRepository.save(teacher1);

        Teacher teacher2 = Teacher.builder()
                .fullName("Петрова Анна Сергеевна")
                .department("Физика")
                .position("Профессор")
                .rate(36.0)
                .phone("+7-900-222-22-22")
                .build();
        teacher2 = teacherRepository.save(teacher2);

        User user2 = User.builder()
                .username("petrova")
                .email("petrova@example.com")
                .password(passwordEncoder.encode("teacher123"))
                .role(User.Role.TEACHER)
                .teacher(teacher2)
                .firstName("Анна")
                .lastName("Петрова")
                .phone("+7-900-222-22-22")
                .build();
        userRepository.save(user2);
        teacher2.setUser(user2);
        teacherRepository.save(teacher2);

        Teacher teacher3 = Teacher.builder()
                .fullName("Сидоров Алексей Петрович")
                .department("Информатика")
                .position("Старший преподаватель")
                .rate(36.0)
                .phone("+7-900-333-33-33")
                .build();
        teacher3 = teacherRepository.save(teacher3);

        User user3 = User.builder()
                .username("sidorov")
                .email("sidorov@example.com")
                .password(passwordEncoder.encode("teacher123"))
                .role(User.Role.TEACHER)
                .teacher(teacher3)
                .firstName("Алексей")
                .lastName("Сидоров")
                .phone("+7-900-333-33-33")
                .build();
        userRepository.save(user3);
        teacher3.setUser(user3);
        teacherRepository.save(teacher3);

        // Создаём группы
        StudyGroup group1 = StudyGroup.builder().name("ИТ-101").course(1).specialty("Информационные технологии").build();
        group1 = groupRepository.save(group1);
        StudyGroup group2 = StudyGroup.builder().name("ИТ-201").course(2).specialty("Информационные технологии").build();
        group2 = groupRepository.save(group2);
        StudyGroup group3 = StudyGroup.builder().name("П-101").course(1).specialty("Программирование").build();
        group3 = groupRepository.save(group3);
        StudyGroup group4 = StudyGroup.builder().name("П-201").course(2).specialty("Программирование").build();
        group4 = groupRepository.save(group4);

        // Создаём дисциплины
        Discipline disc1 = Discipline.builder().name("Математический анализ").code("MA.101").build();
        disc1 = disciplineRepository.save(disc1);
        Discipline disc2 = Discipline.builder().name("Программирование на Java").code("CS.201").build();
        disc2 = disciplineRepository.save(disc2);
        Discipline disc3 = Discipline.builder().name("Физика").code("PH.101").build();
        disc3 = disciplineRepository.save(disc3);
        Discipline disc4 = Discipline.builder().name("Базы данных").code("CS.301").build();
        disc4 = disciplineRepository.save(disc4);
        Discipline disc5 = Discipline.builder().name("Операционные системы").code("CS.401").build();
        disc5 = disciplineRepository.save(disc5);
        Discipline disc6 = Discipline.builder().name("Линейная алгебра").code("MA.201").build();
        disc6 = disciplineRepository.save(disc6);

        Integer academicYear = 2026;

        // Создаём нагрузки (TeacherLoad)
        List<TeacherLoad> loads = new ArrayList<>();

        // Иванов - Математический анализ для ИТ-101
        TeacherLoad load1 = TeacherLoad.builder()
                .teacher(teacher1)
                .group(group1)
                .discipline(disc1)
                .plannedHours(108)
                .firstSemesterHours(54)
                .secondSemesterHours(54)
                .readHours(42)
                .academicYear(academicYear)
                .controlPointType1("Зачёт")
                .controlPointType2("Экзамен")
                .build();
        loads.add(loadRepository.save(load1));

        // Иванов - Линейная алгебра для П-101
        TeacherLoad load2 = TeacherLoad.builder()
                .teacher(teacher1)
                .group(group3)
                .discipline(disc6)
                .plannedHours(72)
                .firstSemesterHours(36)
                .secondSemesterHours(36)
                .readHours(18)
                .academicYear(academicYear)
                .controlPointType1("Зачёт")
                .controlPointType2("Зачёт")
                .build();
        loads.add(loadRepository.save(load2));

        // Петрова - Физика для ИТ-101
        TeacherLoad load3 = TeacherLoad.builder()
                .teacher(teacher2)
                .group(group1)
                .discipline(disc3)
                .plannedHours(86)
                .firstSemesterHours(43)
                .secondSemesterHours(43)
                .readHours(30)
                .academicYear(academicYear)
                .controlPointType1("Зачёт")
                .controlPointType2("Экзамен")
                .build();
        loads.add(loadRepository.save(load3));

        // Петрова - Физика для П-201
        TeacherLoad load4 = TeacherLoad.builder()
                .teacher(teacher2)
                .group(group4)
                .discipline(disc3)
                .plannedHours(86)
                .firstSemesterHours(43)
                .secondSemesterHours(43)
                .readHours(50)
                .academicYear(academicYear)
                .controlPointType1("Зачёт")
                .controlPointType2("Экзамен")
                .build();
        loads.add(loadRepository.save(load4));

        // Сидоров - Программирование на Java для ИТ-201
        TeacherLoad load5 = TeacherLoad.builder()
                .teacher(teacher3)
                .group(group2)
                .discipline(disc2)
                .plannedHours(144)
                .firstSemesterHours(72)
                .secondSemesterHours(72)
                .readHours(60)
                .academicYear(academicYear)
                .controlPointType1("Зачёт")
                .controlPointType2("Экзамен")
                .build();
        loads.add(loadRepository.save(load5));

        // Сидоров - Базы данных для ИТ-201
        TeacherLoad load6 = TeacherLoad.builder()
                .teacher(teacher3)
                .group(group2)
                .discipline(disc4)
                .plannedHours(72)
                .firstSemesterHours(36)
                .secondSemesterHours(36)
                .readHours(20)
                .academicYear(academicYear)
                .controlPointType1("Зачёт")
                .controlPointType2("Экзамен")
                .build();
        loads.add(loadRepository.save(load6));

        // Сидоров - Операционные системы для П-201
        TeacherLoad load7 = TeacherLoad.builder()
                .teacher(teacher3)
                .group(group4)
                .discipline(disc5)
                .plannedHours(86)
                .firstSemesterHours(43)
                .secondSemesterHours(43)
                .readHours(10)
                .academicYear(academicYear)
                .controlPointType1("Зачёт")
                .controlPointType2("Экзамен")
                .build();
        loads.add(loadRepository.save(load7));

        // Создаём расписание пар (Schedule)
        List<Schedule> schedules = new ArrayList<>();

        // Иванов - Матанализ, ИТ-101
        schedules.add(Schedule.builder()
                .teacherLoad(load1)
                .dayOfWeek(DayOfWeek.MONDAY)
                .startTime(LocalTime.of(8, 30))
                .endTime(LocalTime.of(10, 0))
                .classroom("Ауд. 301")
                .academicWeek(1)
                .academicYear(academicYear)
                .build());
        schedules.add(Schedule.builder()
                .teacherLoad(load1)
                .dayOfWeek(DayOfWeek.WEDNESDAY)
                .startTime(LocalTime.of(10, 15))
                .endTime(LocalTime.of(11, 45))
                .classroom("Ауд. 301")
                .academicWeek(null)
                .academicYear(academicYear)
                .build());
        schedules.add(Schedule.builder()
                .teacherLoad(load1)
                .dayOfWeek(DayOfWeek.FRIDAY)
                .startTime(LocalTime.of(12, 0))
                .endTime(LocalTime.of(13, 30))
                .classroom("Ауд. 301")
                .academicWeek(null)
                .academicYear(academicYear)
                .build());

        // Иванов - Линейная алгебра, П-101
        schedules.add(Schedule.builder()
                .teacherLoad(load2)
                .dayOfWeek(DayOfWeek.TUESDAY)
                .startTime(LocalTime.of(8, 30))
                .endTime(LocalTime.of(10, 0))
                .classroom("Ауд. 205")
                .academicWeek(null)
                .academicYear(academicYear)
                .build());
        schedules.add(Schedule.builder()
                .teacherLoad(load2)
                .dayOfWeek(DayOfWeek.THURSDAY)
                .startTime(LocalTime.of(10, 15))
                .endTime(LocalTime.of(11, 45))
                .classroom("Ауд. 205")
                .academicWeek(null)
                .academicYear(academicYear)
                .build());

        // Петрова - Физика, ИТ-101
        schedules.add(Schedule.builder()
                .teacherLoad(load3)
                .dayOfWeek(DayOfWeek.MONDAY)
                .startTime(LocalTime.of(10, 15))
                .endTime(LocalTime.of(11, 45))
                .classroom("Ауд. 410")
                .academicWeek(null)
                .academicYear(academicYear)
                .build());
        schedules.add(Schedule.builder()
                .teacherLoad(load3)
                .dayOfWeek(DayOfWeek.WEDNESDAY)
                .startTime(LocalTime.of(12, 0))
                .endTime(LocalTime.of(13, 30))
                .classroom("Ауд. 410")
                .academicWeek(null)
                .academicYear(academicYear)
                .build());

        // Петрова - Физика, П-201
        schedules.add(Schedule.builder()
                .teacherLoad(load4)
                .dayOfWeek(DayOfWeek.TUESDAY)
                .startTime(LocalTime.of(12, 0))
                .endTime(LocalTime.of(13, 30))
                .classroom("Ауд. 410")
                .academicWeek(null)
                .academicYear(academicYear)
                .build());
        schedules.add(Schedule.builder()
                .teacherLoad(load4)
                .dayOfWeek(DayOfWeek.THURSDAY)
                .startTime(LocalTime.of(14, 0))
                .endTime(LocalTime.of(15, 30))
                .classroom("Ауд. 410")
                .academicWeek(null)
                .academicYear(academicYear)
                .build());

        // Сидоров - Java, ИТ-201
        schedules.add(Schedule.builder()
                .teacherLoad(load5)
                .dayOfWeek(DayOfWeek.MONDAY)
                .startTime(LocalTime.of(14, 0))
                .endTime(LocalTime.of(15, 30))
                .classroom("Ауд. 501")
                .academicWeek(null)
                .academicYear(academicYear)
                .build());
        schedules.add(Schedule.builder()
                .teacherLoad(load5)
                .dayOfWeek(DayOfWeek.WEDNESDAY)
                .startTime(LocalTime.of(8, 30))
                .endTime(LocalTime.of(10, 0))
                .classroom("Ауд. 501")
                .academicWeek(null)
                .academicYear(academicYear)
                .build());
        schedules.add(Schedule.builder()
                .teacherLoad(load5)
                .dayOfWeek(DayOfWeek.FRIDAY)
                .startTime(LocalTime.of(14, 0))
                .endTime(LocalTime.of(15, 30))
                .classroom("Ауд. 501")
                .academicWeek(null)
                .academicYear(academicYear)
                .build());

        // Сидоров - Базы данных, ИТ-201
        schedules.add(Schedule.builder()
                .teacherLoad(load6)
                .dayOfWeek(DayOfWeek.THURSDAY)
                .startTime(LocalTime.of(8, 30))
                .endTime(LocalTime.of(10, 0))
                .classroom("Ауд. 501")
                .academicWeek(null)
                .academicYear(academicYear)
                .build());
        schedules.add(Schedule.builder()
                .teacherLoad(load6)
                .dayOfWeek(DayOfWeek.THURSDAY)
                .startTime(LocalTime.of(10, 15))
                .endTime(LocalTime.of(11, 45))
                .classroom("Ауд. 501")
                .academicWeek(null)
                .academicYear(academicYear)
                .build());

        // Сидоров - ОС, П-201
        schedules.add(Schedule.builder()
                .teacherLoad(load7)
                .dayOfWeek(DayOfWeek.FRIDAY)
                .startTime(LocalTime.of(8, 30))
                .endTime(LocalTime.of(10, 0))
                .classroom("Ауд. 502")
                .academicWeek(null)
                .academicYear(academicYear)
                .build());
        schedules.add(Schedule.builder()
                .teacherLoad(load7)
                .dayOfWeek(DayOfWeek.FRIDAY)
                .startTime(LocalTime.of(10, 15))
                .endTime(LocalTime.of(11, 45))
                .classroom("Ауд. 502")
                .academicWeek(null)
                .academicYear(academicYear)
                .build());

        scheduleRepository.saveAll(schedules);

        // Создаём помесячные записи (MonthlyRecord) для каждой нагрузки
        for (TeacherLoad load : loads) {
            List<MonthlyRecord> existing = monthlyRecordRepository.findByTeacherLoadId(load.getId());
            if (existing.isEmpty()) {
                String[] monthNames = {"", "январь", "февраль", "март", "апрель", "май", "июнь",
                        "июль", "август", "сентябрь", "октябрь", "ноябрь", "декабрь"};

                // Сентябрь-декабрь (1-4) - первая семестровая часть
                int[][] monthlyHours = {
                        {3, 4, 2, 4, 0, 0, 0, 0, 4, 3, 2, 1}, // Матанализ
                        {2, 2, 1, 2, 0, 0, 0, 0, 2, 1, 1, 0}, // Линейная алгебра
                        {3, 2, 3, 2, 0, 0, 0, 0, 2, 3, 1, 0}, // Физика ИТ-101
                        {2, 3, 2, 3, 0, 0, 0, 0, 3, 2, 2, 0}, // Физика П-201
                        {6, 5, 6, 5, 0, 0, 0, 0, 5, 6, 4, 2}, // Java
                        {3, 2, 3, 2, 0, 0, 0, 0, 2, 2, 1, 0}, // Базы данных
                        {3, 2, 2, 3, 0, 0, 0, 0, 2, 2, 1, 0}, // ОС
                };
                int loadIndex = loads.indexOf(load);
                int[] hours = monthlyHours[Math.min(loadIndex, monthlyHours.length - 1)];

                for (int month = 1; month <= 12; month++) {
                    MonthlyRecord record = MonthlyRecord.builder()
                            .teacherLoad(load)
                            .month(month)
                            .year(academicYear)
                            .hours(hours[month - 1])
                            .build();
                    monthlyRecordRepository.save(record);
                }
                log.info("Created monthly records for {}: {} ({})",
                        load.getTeacher().getFullName(),
                        load.getDiscipline().getName(),
                        load.getGroup().getName());
            }
        }

        log.info("Application data initialization completed");
        log.info("Admin login: admin@example.com / admin123");
        log.info("Teacher 1 login: ivanov@example.com / teacher123");
        log.info("Teacher 2 login: petrova@example.com / teacher123");
        log.info("Teacher 3 login: sidorov@example.com / teacher123");
    }
}
