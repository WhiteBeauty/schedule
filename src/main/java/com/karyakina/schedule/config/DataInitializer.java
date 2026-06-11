package com.karyakina.schedule.config;

import com.karyakina.schedule.domain.*;
import com.karyakina.schedule.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.Month;
import java.util.Arrays;
import java.util.List;

@Component
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    private final UserRepository userRepository;
    private final TeacherRepository teacherRepository;
    private final StudyGroupRepository groupRepository;
    private final DisciplineRepository disciplineRepository;
    private final TeacherLoadRepository loadRepository;
    private final CuratorshipRepository curatorshipRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public void run(String... args) {
        // Создаём администратора
        if (!userRepository.existsByEmail("admin@school.ru")) {
            User admin = User.builder()
                    .username("admin")
                    .email("admin@school.ru")
                    .password(passwordEncoder.encode("admin123"))
                    .role(User.Role.ADMIN)
                    .firstName("Администратор")
                    .lastName("Системы")
                    .phone("+79990000000")
                    .birthDate(LocalDate.of(1980, 1, 1))
                    .build();
            userRepository.save(admin);
            System.out.println("✅ Администратор создан: admin@school.ru / admin123");
        }

        // Создаём тестовых преподавателей
        Teacher teacher1 = createTeacher("Иванов Иван Иванович", "Кафедра математики", "Профессор",
                "ivanov@school.ru", "+79001111111", LocalDate.of(1975, 5, 15), "123456");

        Teacher teacher2 = createTeacher("Петрова Анна Сергеевна", "Кафедра физики", "Доцент",
                "petrova@school.ru", "+79002222222", LocalDate.of(1982, 8, 20), "123456");

        Teacher teacher3 = createTeacher("Сидоров Петр Александрович", "Кафедра информатики", "Старший преподаватель",
                "sidorov@school.ru", "+79003333333", LocalDate.of(1988, 3, 10), "123456");

        // Создаём группы
        StudyGroup group1 = createGroup("ИВТ-301", "Информационные технологии", 3, 25);
        StudyGroup group2 = createGroup("ИВТ-302", "Информационные технологии", 3, 28);
        StudyGroup group3 = createGroup("ФИЗ-201", "Прикладная физика", 2, 22);
        StudyGroup group4 = createGroup("МАТ-401", "Прикладная математика", 4, 20);

        // Создаём дисциплины
        Discipline d1 = createDiscipline("Высшая математика", "МATH-101");
        Discipline d2 = createDiscipline("Физика", "PHYS-101");
        Discipline d3 = createDiscipline("Программирование", "CS-201");
        Discipline d4 = createDiscipline("Базы данных", "CS-202");
        Discipline d5 = createDiscipline("Статистика", "STAT-101");

        // Нагрузка для Иванова
        createLoad(teacher1, group1, d1, 72, 36, 36, "КР", "Экзамен", 45, 2024);
        createLoad(teacher1, group2, d1, 80, 40, 40, "КР", "Экзамен", 50, 2024);
        createLoad(teacher1, group3, d5, 54, 27, 27, "Зачёт", null, 30, 2024);

        // Нагрузка для Петровой
        createLoad(teacher2, group3, d2, 68, 34, 34, "КР", "Экзамен", 55, 2024);
        createLoad(teacher2, group4, d2, 72, 36, 36, "КР", "Экзамен", 40, 2024);
        createLoad(teacher2, group1, d1, 36, 18, 18, "Зачёт", null, 20, 2024);

        // Нагрузка для Сидорова
        createLoad(teacher3, group1, d3, 108, 54, 54, "КР", "Зачёт", 80, 2024);
        createLoad(teacher3, group2, d3, 108, 54, 54, "КР", "Зачёт", 75, 2024);
        createLoad(teacher3, group1, d4, 72, 36, 36, "КР", "Зачёт", 50, 2024);
        createLoad(teacher3, group2, d4, 72, 36, 36, "КР", "Зачёт", 45, 2024);

        // Кураторство
        createCuratorship(teacher1, group1, 12, Arrays.asList("Собрание", "Экскурсия", "Консультация"), "Иванов И.И.");
        createCuratorship(teacher2, group3, 10, Arrays.asList("Собрание", "Мероприятие"), "Петрова А.С.");
        createCuratorship(teacher3, group1, 8, Arrays.asList("Собрание"), "Сидоров П.А.");

        System.out.println("✅ Тестовые данные загружены успешно!");
        System.out.println("📧 Учителя: ivanov@school.ru, petrova@school.ru, sidorov@school.ru / 123456");
        System.out.println("📧 Админ: admin@school.ru / admin123");
    }

    private Teacher createTeacher(String fullName, String department, String position,
                                   String email, String phone, LocalDate birthDate, String password) {
        Teacher existingTeacher = teacherRepository.findAll().stream()
                .filter(t -> t.getEmail().equals(email))
                .findFirst()
                .orElse(null);

        if (existingTeacher != null) {
            System.out.println("✅ Преподаватель уже существует: " + fullName);
            return existingTeacher;
        }

        if (!userRepository.existsByEmail(email)) {
            User user = User.builder()
                    .username(fullName.split(" ")[1].toLowerCase())
                    .email(email)
                    .password(passwordEncoder.encode(password))
                    .role(User.Role.TEACHER)
                    .firstName(fullName.split(" ")[1])
                    .lastName(fullName.split(" ")[0])
                    .phone(phone)
                    .birthDate(birthDate)
                    .build();
            userRepository.save(user);

            Teacher teacher = Teacher.builder()
                    .fullName(fullName)
                    .department(department)
                    .position(position)
                    .email(email)
                    .phone(phone)
                    .rate(1.0)
                    .birthDate(birthDate)
                    .user(user)
                    .build();
            teacher = teacherRepository.save(teacher);
            user.setTeacher(teacher);
            userRepository.save(user);
            System.out.println("✅ Преподаватель создан: " + fullName);
            return teacher;
        }

        throw new RuntimeException("User with email " + email + " already exists without teacher profile");
    }

    private StudyGroup createGroup(String name, String specialty, int course, int studentCount) {
        return groupRepository.findByname(name)
                .orElseGet(() -> groupRepository.save(StudyGroup.builder()
                        .name(name)
                        .specialty(specialty)
                        .course(course)
                        .studentCount(studentCount)
                        .build()));
    }

    private Discipline createDiscipline(String name, String code) {
        return disciplineRepository.findByname(name)
                .orElseGet(() -> disciplineRepository.save(Discipline.builder()
                        .name(name)
                        .code(code)
                        .build()));
    }

    private void createLoad(Teacher teacher, StudyGroup group, Discipline discipline,
                            int plannedHours, int firstSemester, int secondSemester,
                            String cp1, String cp2, int readHours, int year) {
        TeacherLoad load = TeacherLoad.builder()
                .teacher(teacher)
                .group(group)
                .discipline(discipline)
                .plannedHours(plannedHours)
                .firstSemesterHours(firstSemester)
                .secondSemesterHours(secondSemester)
                .controlPointType1(cp1)
                .controlPointType2(cp2)
                .readHours(readHours)
                .academicYear(year)
                .build();
        load = loadRepository.save(load);

        // Создаём помесячные записи
        for (int month = 9; month <= 12; month++) {
            MonthlyRecord record = MonthlyRecord.builder()
                    .teacherLoad(load)
                    .month(month)
                    .year(year)
                    .hours(Math.max(0, readHours / 4))
                    .build();
            load.getMonthlyRecords().add(record);
        }
    }

    private void createCuratorship(Teacher teacher, StudyGroup group, int hours,
                                    List<String> events, String responsible) {
        curatorshipRepository.findByTeacherIdAndGroupId(teacher.getId(), group.getId())
                .orElseGet(() -> curatorshipRepository.save(Curatorship.builder()
                        .teacher(teacher)
                        .group(group)
                        .hours(hours)
                        .events(events)
                        .responsiblePerson(responsible)
                        .build()));
    }
}
