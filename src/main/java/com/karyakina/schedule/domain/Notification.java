package com.karyakina.schedule.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "notifications")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "recipient_teacher_id")
    private Teacher recipientTeacher;

    @Column(nullable = false)
    @Builder.Default
    private Boolean forAdmins = false;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 64)
    private Type type;

    @Column(nullable = false)
    private String title;

    @Column(length = 2000)
    private String message;

    private Long substitutionRequestId;

    private String linkUrl;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isRead = false;

    @CreationTimestamp
    private LocalDateTime createdAt;

    public enum Type {
        SUBSTITUTION_REQUEST,
        SUBSTITUTION_ACCEPTED,
        SUBSTITUTION_DECLINED,
        SUBSTITUTION_UNRESOLVED,
        IMPORT_REPORT,
        SCHEDULE_CONFLICT,
        SICK_LEAVE,
        SCHEDULE_CHANGED,
        LOAD_CHANGED,
        INFO
    }
}
