package com.karyakina.schedule.component;

import com.karyakina.schedule.domain.TeacherLoad;
import com.karyakina.schedule.domain.Tarification;
import com.karyakina.schedule.repository.TarificationRepository;
import com.karyakina.schedule.repository.TeacherLoadRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
@org.springframework.core.annotation.Order(1)
public class TarificationBackfill implements ApplicationRunner {

    private final TeacherLoadRepository loadRepository;
    private final TarificationRepository tarificationRepository;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        List<TeacherLoad> orphaned = loadRepository.findByTarificationIsNull();
        if (orphaned.isEmpty()) {
            return;
        }
        Map<Integer, List<TeacherLoad>> byYear = orphaned.stream()
                .collect(Collectors.groupingBy(TeacherLoad::getAcademicYear));
        for (Map.Entry<Integer, List<TeacherLoad>> entry : byYear.entrySet()) {
            Tarification legacy = tarificationRepository.save(Tarification.builder()
                    .name("Загружено до введения версий тарификации")
                    .academicYear(entry.getKey())
                    .importedBy("system")
                    .build());
            entry.getValue().forEach(load -> load.setTarification(legacy));
            loadRepository.saveAll(entry.getValue());
            log.info("Привязано {} записей нагрузки без тарификации к «{}» ({} год)",
                    entry.getValue().size(), legacy.getName(), entry.getKey());
        }
    }
}
