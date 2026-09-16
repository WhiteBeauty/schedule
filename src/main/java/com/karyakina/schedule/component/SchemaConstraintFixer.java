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

@Component
@Slf4j
@org.springframework.core.annotation.Order(1)
public class SchemaConstraintFixer implements ApplicationRunner {

    @PersistenceContext
    private EntityManager entityManager;

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
        relaxNotNull("teacher_loads", "teacher_id");
    }

    private void relaxNotNull(String table, String column) {
        try {
            entityManager.createNativeQuery(
                    "ALTER TABLE " + table + " ALTER COLUMN " + column + " DROP NOT NULL"
            ).executeUpdate();
            log.info("Ensured {}.{} allows NULL", table, column);
        } catch (Exception e) {
            log.debug("Skipping NOT NULL relax for {}.{}: {}", table, column, e.getMessage());
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
            log.debug("Skipping constraint check for {}.{}: {}", table, column, e.getMessage());
        }
    }
}
