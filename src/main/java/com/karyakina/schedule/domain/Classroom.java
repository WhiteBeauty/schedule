package com.karyakina.schedule.domain;

import jakarta.persistence.*;
import lombok.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Аудитория/кабинет. Раньше фонд аудиторий был захардкожен константой в
 * {@code GenerationGrid} — теперь он читается из БД (см. {@code GenerationGrid.rooms(List)}),
 * а сюда данные попадают либо вручную, либо импортом из Excel (лист с колонкой
 * "Аудитории"/"Аудитория"/"Кабинет" — см. {@code ImportService}).
 */
@Entity
@Table(name = "classrooms")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Classroom {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    /** Вместимость (человек). Не задана — аудитория не ограничивает размер группы. */
    private Integer capacity;

    /**
     * Дисциплины, которые разрешено проводить в этой аудитории (сравнение по названию,
     * без учёта регистра). Пустой список — аудитория открыта для ЛЮБЫХ дисциплин
     * (обычная семинарская/лекционная аудитория без специализации, например лаборатория
     * не указана явно). Заполняется только если в файле импорта для этой аудитории
     * явно перечислены дисциплины (например, специализированная лаборатория).
     */
    @ElementCollection
    @CollectionTable(name = "classroom_allowed_disciplines", joinColumns = @JoinColumn(name = "classroom_id"))
    @Column(name = "discipline_name")
    @Builder.Default
    private List<String> allowedDisciplines = new ArrayList<>();
}
