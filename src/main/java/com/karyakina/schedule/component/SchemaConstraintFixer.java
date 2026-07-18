package com.karyakina.schedule.component;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Hibernate ddl-auto=update не обновляет PostgreSQL CHECK-ограничения для enum.
 * После добавления новых значений в Notification.Type старый CHECK блокирует INSERT
 * (например SICK_LEAVE) — из‑за этого уведомления о больничном не сохранялись.
 */
@Component
@Slf4j
public class SchemaConstraintFixer implements ApplicationRunner {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        try {
            entityManager.createNativeQuery(
                    "ALTER TABLE notifications DROP CONSTRAINT IF EXISTS notifications_type_check"
            ).executeUpdate();
            log.info("Dropped notifications_type_check so Notification.Type values are not blocked by stale CHECK");
        } catch (Exception e) {
            log.warn("Could not drop notifications_type_check: {}", e.getMessage());
        }
    }
}
