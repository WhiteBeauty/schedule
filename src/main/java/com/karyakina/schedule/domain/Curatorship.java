package com.karyakina.schedule.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "curatorships")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Curatorship {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "teacher_id")
    private Teacher teacher;

    @ManyToOne(optional = false)
    @JoinColumn(name = "group_id")
    private StudyGroup group;

    private Integer hours;

    @ElementCollection
    @CollectionTable(name = "curatorship_events_v2", joinColumns = @JoinColumn(name = "curatorship_id"))
    @OrderColumn(name = "event_order")
    @Builder.Default
    private List<CuratorEvent> events = new ArrayList<>();

    @ElementCollection
    @CollectionTable(name = "curatorship_logs", joinColumns = @JoinColumn(name = "curatorship_id"))
    @Column(name = "log_entry")
    @OrderColumn(name = "log_order")
    @Builder.Default
    private List<String> logs = new ArrayList<>();
}
