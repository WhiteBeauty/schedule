package com.karyakina.schedule.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Конкретное занятие на конкретную календарную дату, порождённое шаблоном {@link Schedule}.
 * Это ядро модуля тарификации: именно от статуса LessonInstance зависит, когда и кому
 * начисляются фактические часы (actual_hours / readHours у {@link TeacherLoad}).
 *
 * Жизненный цикл:
 *  PLANNED           — пара стоит в расписании, ещё не наступила/не подтверждена;
 *  CONFIRMED         — пара проведена, часы начислены преподавателю из teacherLoad;
 *  CANCELLED         — пара отменена (например, болел преподаватель, замену не нашли);
 *  REPLACED          — пара проведена другим преподавателем (см. originalTeacher/teacher);
 *  INDEPENDENT_WORK  — пара не проведена очно, группе назначена самостоятельная работа
 *                       (форс-мажор у преподавателя, замену/перенос найти не удалось).
 */
@Entity
@Table(name = "lesson_instances", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"schedule_id", "lesson_date"})
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class LessonInstance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "schedule_id")
    private Schedule schedule;

    @Column(name = "lesson_date", nullable = false)
    private LocalDate lessonDate;

    @Column(nullable = false)
    private Integer academicYear;

    /** Длительность пары в часах (вычисляется из времени начала/конца шаблона на момент создания). */
    @Column(nullable = false)
    private Integer durationHours;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private Status status = Status.PLANNED;

    /**
     * Нагрузка (teacher+group+discipline), на которую в данный момент относятся часы этого
     * занятия. Изначально совпадает с schedule.teacherLoad, но при замене (REPLACED)
     * переключается на нагрузку заменяющего преподавателя.
     */
    @ManyToOne(optional = false)
    @JoinColumn(name = "teacher_load_id")
    private TeacherLoad teacherLoad;

    /** Исходный преподаватель по расписанию (не меняется даже при замене). */
    @ManyToOne(optional = false)
    @JoinColumn(name = "original_teacher_id")
    private Teacher originalTeacher;

    /** Преподаватель, который фактически провёл пару (может совпадать с originalTeacher). */
    @ManyToOne(optional = false)
    @JoinColumn(name = "actual_teacher_id")
    private Teacher actualTeacher;

    private String note;

    private LocalDateTime confirmedAt;
    private LocalDateTime cancelledAt;

    @CreationTimestamp
    private LocalDateTime createdAt;

    /**
     * Если это занятие было отменено из-за экзамена/практики/вождения — id соответствующего
     * {@code SpecialEvent}. Позволяет при удалении события надёжно найти и вернуть в
     * PLANNED именно те занятия, которые он отменил (см. SpecialEventService.deleteEvent).
     */
    private Long cancelledBySpecialEventId;

    public enum Status {
        PLANNED, CONFIRMED, CANCELLED, REPLACED, INDEPENDENT_WORK
    }
}
