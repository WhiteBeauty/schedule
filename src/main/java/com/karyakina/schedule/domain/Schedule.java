package com.karyakina.schedule.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.DayOfWeek;
import java.time.LocalTime;

@Entity
@Table(name = "schedules")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Schedule {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "teacher_load_id")
    private TeacherLoad teacherLoad;

    @Column(nullable = false)
    private DayOfWeek dayOfWeek;

    @Column(nullable = false)
    private LocalTime startTime;

    @Column(nullable = false)
    private LocalTime endTime;

    @Column(nullable = false)
    private String classroom;

    private Integer academicWeek; // 1-18, null = каждая неделя

    @Column(nullable = false)
    private Integer academicYear;

    /**
     * Если не null — эта запись расписания является ПЕРЕНОСОМ пары, изначально стоявшей
     * на эту дату (academicWeek у такой записи почти всегда указывает на ОДНУ конкретную
     * неделю — это "разовое исключение" из недельного шаблона, а не постоянное расписание).
     * Появляется при автопереносе пар из-за экзамена/практики/вождения — см.
     * {@code SpecialEventService}. Используется только для подсветки в UI ("перенесено")
     * и не участвует ни в какой другой логике.
     */
    private java.time.LocalDate rescheduledFromDate;

    /** Причина переноса (для подсказки в UI) — например "экзамен по Истории 12.11.2026". */
    private String rescheduledReason;
}
