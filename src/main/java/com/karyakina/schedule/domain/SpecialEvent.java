package com.karyakina.schedule.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Событие, которое блокирует у группы обычные пары на период и требует переноса
 * конфликтующих пар (ТЗ п.4-9): экзамен (один день), учебная/производственная практика
 * (несколько дней/неделя), вождение (дни без других пар практики). Сама по себе запись —
 * это "что и когда заблокировано у группы"; реальные перестановки пар хранятся как обычные
 * {@link Schedule} с {@code rescheduledFromDate} — см. {@code SpecialEventService}.
 */
@Entity
@Table(name = "special_events")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class SpecialEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "group_id")
    private StudyGroup group;

    /** Для экзамена — предмет, по которому он проходит. Для практики/вождения — null (это диапазон дат, не один предмет). */
    @ManyToOne
    @JoinColumn(name = "discipline_id")
    private Discipline discipline;

    /**
     * Для практики/вождения — нагрузка (TeacherLoad), к которой относятся часы за период,
     * если она уже есть в тарификации (обычно строки "ПП.NN"/"УП.NN"/"Вождение..." —
     * см. {@code ScheduleGeneratorService#isPracticeOrDriving}). Не обязательна: администратор
     * может назначить практику/вождение и без предварительно заведённой нагрузки.
     */
    @ManyToOne
    @JoinColumn(name = "teacher_load_id")
    private TeacherLoad teacherLoad;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Type type;

    @Column(nullable = false)
    private LocalDate startDate;

    /** Для экзамена совпадает со startDate. */
    @Column(nullable = false)
    private LocalDate endDate;

    @Column(nullable = false)
    private Integer academicYear;

    private String createdBy;

    @CreationTimestamp
    private LocalDateTime createdAt;

    /** Свободный текст — например для экзамена "Экзамен: История" на подсветку в календаре. */
    private String note;

    public enum Type {
        EXAM, PRODUCTION_PRACTICE, STUDY_PRACTICE, DRIVING
    }
}
