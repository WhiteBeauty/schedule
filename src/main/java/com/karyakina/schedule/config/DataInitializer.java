package com.karyakina.schedule.config;

import com.karyakina.schedule.domain.*;
import com.karyakina.schedule.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDate;

@Configuration
@RequiredArgsConstructor
public class DataInitializer {

    private final TeacherRepository teacherRepository;
    private final UserRepository userRepository;
    private final StudyGroupRepository groupRepository;
    private final DisciplineRepository disciplineRepository;
    private final TeacherLoadRepository loadRepository;
    private final ControlPointRepository controlPointRepository;
    private final MonthlyRecordRepository monthlyRecordRepository;
    private final CuratorshipRepository curatorshipRepository;
    private final PasswordEncoder passwordEncoder;

    @Bean
    public CommandLineRunner initData() {
        return args -> {
            if (userRepository.count() > 0) {
                return; // Данные уже есть
            }

            // Teachers
            Teacher t1 = teacherRepository.save(Teacher.builder()
                    .fullName("Иванов Сергей Петрович")
                    .department("Кафедра математики")
                    .position("Доцент")
                    .email("ivanov@university.ru")
                    .phone("+79991234567")
                    .rate(1.0)
                    .birthDate(LocalDate.of(1985, 3, 15))
                    .build());

            Teacher t2 = teacherRepository.save(Teacher.builder()
                    .fullName("Петрова Анна Владимировна")
                    .department("Кафедра информатики")
                    .position("Профессор")
                    .email("petrova@university.ru")
                    .phone("+79992345678")
                    .rate(1.0)
                    .birthDate(LocalDate.of(1978, 7, 22))
                    .build());

            Teacher t3 = teacherRepository.save(Teacher.builder()
                    .fullName("Сидоров Дмитрий Алексеевич")
                    .department("Кафедра физики")
                    .position("Старший преподаватель")
                    .email("sidorov@university.ru")
                    .phone("+79993456789")
                    .rate(0.75)
                    .birthDate(LocalDate.of(1990, 11, 5))
                    .build());

            Teacher t4 = teacherRepository.save(Teacher.builder()
                    .fullName("Кузнецова Елена Олеговна")
                    .department("Кафедра математики")
                    .position("Ассистент")
                    .email("kuznetsova@university.ru")
                    .phone("+79994567890")
                    .rate(0.5)
                    .birthDate(LocalDate.of(1995, 1, 30))
                    .build());

            // Users
            userRepository.save(User.builder()
                    .username("teacher1")
                    .password(passwordEncoder.encode("teacher123"))
                    .role(User.Role.TEACHER)
                    .teacher(t1)
                    .build());

            userRepository.save(User.builder()
                    .username("admin")
                    .password(passwordEncoder.encode("admin123"))
                    .role(User.Role.ADMIN)
                    .build());

            // Study groups
            StudyGroup g1 = groupRepository.save(StudyGroup.builder()
                    .name("ИВТ-101")
                    .specialty("Информатика и вычислительная техника")
                    .course(1)
                    .studentCount(25)
                    .build());

            StudyGroup g2 = groupRepository.save(StudyGroup.builder()
                    .name("ИВТ-201")
                    .specialty("Информатика и вычислительная техника")
                    .course(2)
                    .studentCount(22)
                    .build());

            StudyGroup g3 = groupRepository.save(StudyGroup.builder()
                    .name("МТ-301")
                    .specialty("Математика и механика")
                    .course(3)
                    .studentCount(18)
                    .build());

            StudyGroup g4 = groupRepository.save(StudyGroup.builder()
                    .name("ФИЗ-101")
                    .specialty("Физика")
                    .course(1)
                    .studentCount(20)
                    .build());

            // Disciplines
            Discipline d1 = disciplineRepository.save(Discipline.builder().name("Высшая математика").code("MAT-101").build());
            Discipline d2 = disciplineRepository.save(Discipline.builder().name("Программирование на Java").code("CS-201").build());
            Discipline d3 = disciplineRepository.save(Discipline.builder().name("Алгоритмы и структуры данных").code("CS-301").build());
            Discipline d4 = disciplineRepository.save(Discipline.builder().name("Общая физика").code("PHY-101").build());
            Discipline d5 = disciplineRepository.save(Discipline.builder().name("Базы данных").code("CS-202").build());
            Discipline d6 = disciplineRepository.save(Discipline.builder().name("Дискретная математика").code("MAT-201").build());

            // Teacher loads
            TeacherLoad l1 = loadRepository.save(TeacherLoad.builder()
                    .teacher(t1).group(g1).discipline(d1)
                    .plannedHours(120).firstSemesterHours(60).secondSemesterHours(60)
                    .controlPointType1("Зачёт").controlPointType2("Экзамен")
                    .readHours(85).academicYear(2024).build());

            TeacherLoad l2 = loadRepository.save(TeacherLoad.builder()
                    .teacher(t1).group(g3).discipline(d1)
                    .plannedHours(90).firstSemesterHours(45).secondSemesterHours(45)
                    .controlPointType1("КР").controlPointType2("-")
                    .readHours(60).academicYear(2024).build());

            TeacherLoad l3 = loadRepository.save(TeacherLoad.builder()
                    .teacher(t2).group(g1).discipline(d2)
                    .plannedHours(100).firstSemesterHours(50).secondSemesterHours(50)
                    .controlPointType1("ДЗ").controlPointType2("Э")
                    .readHours(95).academicYear(2024).build());

            TeacherLoad l4 = loadRepository.save(TeacherLoad.builder()
                    .teacher(t2).group(g2).discipline(d3)
                    .plannedHours(80).firstSemesterHours(40).secondSemesterHours(40)
                    .controlPointType1("Зачёт").controlPointType2("Экзамен")
                    .readHours(70).academicYear(2024).build());

            TeacherLoad l5 = loadRepository.save(TeacherLoad.builder()
                    .teacher(t2).group(g2).discipline(d5)
                    .plannedHours(60).firstSemesterHours(30).secondSemesterHours(30)
                    .controlPointType1("КР").controlPointType2("-")
                    .readHours(30).academicYear(2024).build());

            TeacherLoad l6 = loadRepository.save(TeacherLoad.builder()
                    .teacher(t3).group(g4).discipline(d4)
                    .plannedHours(110).firstSemesterHours(55).secondSemesterHours(55)
                    .controlPointType1("ДЗ").controlPointType2("Экзамен")
                    .readHours(40).academicYear(2024).build());

            TeacherLoad l7 = loadRepository.save(TeacherLoad.builder()
                    .teacher(t4).group(g1).discipline(d6)
                    .plannedHours(70).firstSemesterHours(35).secondSemesterHours(35)
                    .controlPointType1("Зачёт").controlPointType2("-")
                    .readHours(65).academicYear(2024).build());

            // Control points
            controlPointRepository.save(ControlPoint.builder().teacherLoad(l1).type(ControlPoint.ControlPointType.KR).plannedDate(LocalDate.of(2024, 10, 15)).actualDate(LocalDate.of(2024, 10, 15)).status(ControlPoint.ControlPointStatus.ON_TIME).build());
            controlPointRepository.save(ControlPoint.builder().teacherLoad(l1).type(ControlPoint.ControlPointType.ZACHET).plannedDate(LocalDate.of(2024, 12, 20)).actualDate(LocalDate.of(2024, 12, 22)).status(ControlPoint.ControlPointStatus.OVERDUE).build());
            controlPointRepository.save(ControlPoint.builder().teacherLoad(l1).type(ControlPoint.ControlPointType.EXAM).plannedDate(LocalDate.of(2025, 1, 15)).status(ControlPoint.ControlPointStatus.PLANNED).build());
            controlPointRepository.save(ControlPoint.builder().teacherLoad(l2).type(ControlPoint.ControlPointType.KR).plannedDate(LocalDate.of(2024, 11, 1)).actualDate(LocalDate.of(2024, 11, 1)).status(ControlPoint.ControlPointStatus.ON_TIME).build());
            controlPointRepository.save(ControlPoint.builder().teacherLoad(l3).type(ControlPoint.ControlPointType.KR).plannedDate(LocalDate.of(2024, 10, 20)).actualDate(LocalDate.of(2024, 10, 20)).status(ControlPoint.ControlPointStatus.ON_TIME).build());
            controlPointRepository.save(ControlPoint.builder().teacherLoad(l3).type(ControlPoint.ControlPointType.ZACHET).plannedDate(LocalDate.of(2024, 12, 25)).status(ControlPoint.ControlPointStatus.PLANNED).build());
            controlPointRepository.save(ControlPoint.builder().teacherLoad(l4).type(ControlPoint.ControlPointType.KR).plannedDate(LocalDate.of(2024, 11, 10)).actualDate(LocalDate.of(2024, 11, 12)).status(ControlPoint.ControlPointStatus.OVERDUE).build());
            controlPointRepository.save(ControlPoint.builder().teacherLoad(l5).type(ControlPoint.ControlPointType.ZACHET).plannedDate(LocalDate.of(2024, 11, 20)).status(ControlPoint.ControlPointStatus.PLANNED).build());
            controlPointRepository.save(ControlPoint.builder().teacherLoad(l6).type(ControlPoint.ControlPointType.KR).plannedDate(LocalDate.of(2024, 10, 5)).status(ControlPoint.ControlPointStatus.PLANNED).build());
            controlPointRepository.save(ControlPoint.builder().teacherLoad(l7).type(ControlPoint.ControlPointType.EXAM).plannedDate(LocalDate.of(2024, 12, 15)).actualDate(LocalDate.of(2024, 12, 15)).status(ControlPoint.ControlPointStatus.ON_TIME).build());

            // Monthly records
            createMonthlyRecord(l1, 9, 2024, 20, null, "", "system");
            createMonthlyRecord(l1, 10, 2024, 25, null, "", "system");
            createMonthlyRecord(l1, 11, 2024, 22, 24, "Доп. занятия", "admin");
            createMonthlyRecord(l1, 12, 2024, 18, null, "", "system");
            createMonthlyRecord(l2, 9, 2024, 15, null, "", "system");
            createMonthlyRecord(l2, 10, 2024, 20, null, "", "system");
            createMonthlyRecord(l2, 11, 2024, 15, null, "", "system");
            createMonthlyRecord(l2, 12, 2024, 10, null, "", "system");
            createMonthlyRecord(l3, 9, 2024, 20, null, "", "system");
            createMonthlyRecord(l3, 10, 2024, 25, null, "", "system");
            createMonthlyRecord(l3, 11, 2024, 25, null, "", "system");
            createMonthlyRecord(l3, 12, 2024, 25, null, "", "system");
            createMonthlyRecord(l4, 9, 2024, 15, null, "", "system");
            createMonthlyRecord(l4, 10, 2024, 20, null, "", "system");
            createMonthlyRecord(l4, 11, 2024, 20, null, "", "system");
            createMonthlyRecord(l4, 12, 2024, 15, null, "", "system");
            createMonthlyRecord(l5, 9, 2024, 8, null, "", "system");
            createMonthlyRecord(l5, 10, 2024, 10, null, "", "system");
            createMonthlyRecord(l5, 11, 2024, 12, null, "", "system");
            createMonthlyRecord(l6, 9, 2024, 10, null, "", "system");
            createMonthlyRecord(l6, 10, 2024, 15, null, "", "system");
            createMonthlyRecord(l6, 11, 2024, 15, null, "", "system");
            createMonthlyRecord(l7, 9, 2024, 15, null, "", "system");
            createMonthlyRecord(l7, 10, 2024, 20, null, "", "system");
            createMonthlyRecord(l7, 11, 2024, 15, null, "", "system");
            createMonthlyRecord(l7, 12, 2024, 15, null, "", "system");

            // Curatorships
            Curatorship c1 = curatorshipRepository.save(Curatorship.builder()
                    .teacher(t1).group(g1).hours(36).responsiblePerson("Иванов С.П.").build());
            c1.getEvents().add("Встреча первокурсников");
            c1.getEvents().add("Профилактическая беседа");
            c1.getEvents().add("День открытых дверей кафедры");
            c1.getLogs().add("2024-09-05: Назначен куратором группы ИВТ-101");
            c1.getLogs().add("2024-10-12: Проведено собрание о промежуточной аттестации");
            curatorshipRepository.save(c1);

            Curatorship c2 = curatorshipRepository.save(Curatorship.builder()
                    .teacher(t2).group(g2).hours(36).responsiblePerson("Петрова А.В.").build());
            c2.getEvents().add("Семинар по профориентации");
            c2.getEvents().add("Встреча с работодателями");
            c2.getLogs().add("2024-09-10: Назначена куратором группы ИВТ-201");
            curatorshipRepository.save(c2);

            Curatorship c3 = curatorshipRepository.save(Curatorship.builder()
                    .teacher(t3).group(g4).hours(24).responsiblePerson("Сидоров Д.А.").build());
            c3.getEvents().add("Научный кружок");
            c3.getLogs().add("2024-09-15: Назначен куратором группы ФИЗ-101");
            curatorshipRepository.save(c3);
        };
    }

    private void createMonthlyRecord(TeacherLoad load, int month, int year, int hours, Integer adjusted, String note, String changedBy) {
        monthlyRecordRepository.save(MonthlyRecord.builder()
                .teacherLoad(load)
                .month(month)
                .year(year)
                .hours(hours)
                .adjustedHours(adjusted)
                .note(note)
                .changedBy(changedBy)
                .build());
    }
}
