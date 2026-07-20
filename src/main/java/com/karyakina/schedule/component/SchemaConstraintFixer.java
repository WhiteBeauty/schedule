package com.karyakina.schedule.component;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Tuple;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Hibernate ddl-auto=update не обновляет PostgreSQL CHECK-ограничения для enum-колонок:
 * если в Java-enum добавили новое значение (например, LessonInstance.Status.REPLACED
 * или SubstitutionRequest.Status.EXPIRED), старый CHECK на уже существующей таблице
 * продолжает разрешать только прежний набор значений и блокирует INSERT/UPDATE.
 *
 * Это САМАЯ частая причина, по которой операции модуля форс-мажоров (создание
 * SubstitutionRequest, обновление LessonInstance.status, Notification.type) тихо падали
 * с исключением на уровне БД — а так как весь SubstitutionService.handleNewSickLeave()
 * выполняется в одной транзакции (@Transactional), откатывались вообще ВСЕ изменения
 * этого вызова, включая самое первое уведомление администратору. Внешне это выглядело
 * так, будто "при добавлении больничного вообще ничего не произошло".
 *
 * Решение: при каждом старте ищем все CHECK-ограничения на колонках, которые Hibernate
 * отобразил из Java-enum'ов, и удаляем их — корректность значений и так гарантируется
 * приложением через @Enumerated(EnumType.STRING), отдельный CHECK в БД не нужен.
 */
@Component
@Slf4j
@org.springframework.core.annotation.Order(1)
public class SchemaConstraintFixer implements ApplicationRunner {

    @PersistenceContext
    private EntityManager entityManager;

    /** table -> enum-backed column, для которых Hibernate мог оставить устаревший CHECK. */
    private static final String[][] ENUM_COLUMNS = {
            {"notifications", "type"},
            {"substitution_requests", "status"},
            {"lesson_instances", "status"},
            {"control_points", "control_type"},
            {"control_points", "status"},
            {"users", "role"},
    };

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        for (String[] pair : ENUM_COLUMNS) {
            dropChecksForColumn(pair[0], pair[1]);
        }
    }

    @SuppressWarnings("unchecked")
    private void dropChecksForColumn(String table, String column) {
        try {
            List<Tuple> constraints = entityManager.createNativeQuery(
                    "SELECT DISTINCT con.conname AS conname " +
                    "FROM pg_constraint con " +
                    "JOIN pg_class rel ON rel.oid = con.conrelid " +
                    "JOIN pg_attribute att ON att.attrelid = con.conrelid AND att.attnum = ANY(con.conkey) " +
                    "WHERE con.contype = 'c' AND rel.relname = :table AND att.attname = :column",
                    Tuple.class)
                    .setParameter("table", table)
                    .setParameter("column", column)
                    .getResultList();

            for (Tuple row : constraints) {
                String conname = (String) row.get("conname");
                try {
                    entityManager.createNativeQuery(
                            "ALTER TABLE " + table + " DROP CONSTRAINT IF EXISTS " + conname
                    ).executeUpdate();
                    log.info("Dropped stale CHECK constraint {} on {}.{}", conname, table, column);
                } catch (Exception dropEx) {
                    log.warn("Could not drop constraint {} on {}.{}: {}", conname, table, column, dropEx.getMessage());
                }
            }
        } catch (Exception e) {
            // Таблица/колонка может ещё не существовать при самом первом запуске на пустой
            // БД (Hibernate создаст её позже в рамках ddl-auto=update) — это не ошибка.
            log.debug("Skipping constraint check for {}.{}: {}", table, column, e.getMessage());
        }
    }
}
