package com.karyakina.schedule.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "monthly_records")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class MonthlyRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "teacher_load_id")
    @JsonIgnore
    private TeacherLoad teacherLoad;

    @Column(name = "record_month", nullable = false)
    private Integer month; // 1..12

    @Column(name = "record_year", nullable = false)
    private Integer year;

    /** Плановые часы за месяц (по расписанию/шаблону) — пересчитываются автоматически, см. MonthlyRecordService.recalculateHoursForLoad. */
    @Column(nullable = false)
    private Integer hours;

    /**
     * ФАКТИЧЕСКИ проведённые часы за месяц — начисляются/списываются ТОЛЬКО через
     * подтверждение/отмену конкретного занятия (см. LessonInstanceService.confirmInstance/
     * cancelInstance), никогда не трогаются пересчётом плана. Раньше это поле отсутствовало,
     * и факт с планом были слиты в одно поле {@code hours} — из-за этого пересчёт плана при
     * любом редактировании пары тихо стирал уже подтверждённые часы.
     *
     * БЕЗ {@code nullable = false}: колонка новая, а в таблице уже есть строки —
     * Hibernate/Postgres не могут добавить NOT NULL колонку без значения по умолчанию
     * в непустую таблицу (ALTER TABLE тихо падает, ddl-auto=update молча не создаёт
     * колонку, а приложение потом падает уже громко при первом же SELECT). Код везде
     * трактует null как 0 (см. геттер ниже и остальные места чтения).
     */
    @Column
    @Builder.Default
    private Integer conductedHours = 0;

    /** Скорректированное значение (если ручная правка) */
    private Integer adjustedHours;

    private String note;
    private String changedBy;

    @CreationTimestamp
    private LocalDateTime changedAt;

    @Transient
    public int getEffectiveHours() {
        return adjustedHours != null ? adjustedHours : hours;
    }

    /** null (старые строки, созданные до появления этого поля) трактуется как 0. */
    @Transient
    public int getConductedHoursOrZero() {
        return conductedHours != null ? conductedHours : 0;
    }
}
