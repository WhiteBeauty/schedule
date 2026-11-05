package com.karyakina.schedule.service;

import com.karyakina.schedule.util.AcademicYearUtil;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// досчёт после сохранения идёт в фоне по одному иначе запрос висит минутами и браузер его обрывает
@Service
@RequiredArgsConstructor
@Slf4j
public class AfterSaveService {

    public enum Status { QUEUED, RUNNING, DONE, FAILED }

    public static final class Job {
        private final String id = UUID.randomUUID().toString();
        private final String title;
        private final String sessionId;
        private final int academicYear;
        private final List<Integer> semesters;
        private final Collection<Long> loadIds;
        private final Instant queuedAt = Instant.now();
        private volatile Instant startedAt;
        private volatile Instant finishedAt;
        private volatile Status status = Status.QUEUED;
        private volatile String step = "Ждёт очереди";
        private final List<String> notes = new ArrayList<>();
        private final List<String> errors = new ArrayList<>();

        private Job(String title, String sessionId, int academicYear, List<Integer> semesters, Collection<Long> loadIds) {
            this.title = title;
            this.sessionId = sessionId;
            this.academicYear = academicYear;
            this.semesters = List.copyOf(semesters);
            this.loadIds = loadIds;
        }

        public String getId() {
            return id;
        }

        public Status getStatus() {
            return status;
        }

        synchronized Map<String, Object> view() {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("id", id);
            view.put("title", title);
            view.put("sessionId", sessionId);
            view.put("status", status.name());
            view.put("step", step);
            view.put("queuedAt", queuedAt.toString());
            view.put("finishedAt", finishedAt == null ? null : finishedAt.toString());
            Instant from = startedAt != null ? startedAt : queuedAt;
            view.put("seconds", Duration.between(from, finishedAt != null ? finishedAt : Instant.now()).toSeconds());
            view.put("notes", List.copyOf(notes));
            view.put("errors", List.copyOf(errors));
            return view;
        }
    }

    private final SpecialEventService specialEventService;
    private final ScheduleService scheduleService;
    private final HoursRecalculationService hoursRecalculationService;
    private final SettingsService settingsService;

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "after-save");
        thread.setDaemon(true);
        return thread;
    });
    private final Deque<Job> recent = new ConcurrentLinkedDeque<>();

    // loadIds пустой значит все нагрузки семестра
    public Job submit(String title, String sessionId, int academicYear, List<Integer> semesters, Collection<Long> loadIds) {
        Job job = new Job(title, sessionId, academicYear, semesters, loadIds);
        recent.addFirst(job);
        while (recent.size() > 20) {
            recent.pollLast();
        }
        worker.submit(() -> run(job));
        log.info("Досчёт после сохранения поставлен в очередь: {}", title);
        return job;
    }

    public Optional<Map<String, Object>> latest() {
        Job job = recent.peekFirst();
        return job == null ? Optional.empty() : Optional.of(job.view());
    }

    public boolean isBusy() {
        return recent.stream().anyMatch(j -> j.status == Status.QUEUED || j.status == Status.RUNNING);
    }

    public String busyText() {
        return recent.stream().filter(j -> j.status == Status.QUEUED || j.status == Status.RUNNING)
                .reduce((newer, older) -> older)
                .map(j -> j.title + ": " + j.step.toLowerCase())
                .orElse("");
    }

    private void run(Job job) {
        job.startedAt = Instant.now();
        job.status = Status.RUNNING;
        step(job, "Снимаем пары с дней экзаменов и практик", () -> specialEventService.reapplyEvents(job.academicYear));
        step(job, "Засчитываем прошедшие пары",
                () -> scheduleService.autoDeductHours(AcademicYearUtil.getCurrentAcademicYearStart()));
        if (settingsService.isPaceRecalculationEnabled()) {
            for (int semester : job.semesters) {
                step(job, "Пересчитываем часы " + semester + " семестра", () -> {
                    HoursRecalculationService.Result r = hoursRecalculationService.recalculate(job.academicYear, semester,
                            job.loadIds == null || job.loadIds.isEmpty() ? null : job.loadIds, false);
                    synchronized (job) {
                        job.notes.add(semester + " семестр: добавлено доп. пар " + r.added().size() + ", снято лишних "
                                + r.cancelled().size() + (r.unresolved().isEmpty() ? ""
                                : ", не хватило мест " + r.unresolved().size() + " (см. «Не вставшие пары»)"));
                    }
                });
            }
        }
        synchronized (job) {
            job.finishedAt = Instant.now();
            job.status = job.errors.isEmpty() ? Status.DONE : Status.FAILED;
            job.step = job.errors.isEmpty() ? "Готово" : "Закончено с ошибками";
        }
        log.info("Досчёт после сохранения закончен: {} ({} с){}", job.title,
                Duration.between(job.startedAt, job.finishedAt).toSeconds(),
                job.errors.isEmpty() ? "" : ", ошибки: " + job.errors);
    }

    private void step(Job job, String name, Runnable action) {
        job.step = name;
        try {
            action.run();
        } catch (Exception e) {
            log.error("Досчёт после сохранения, шаг «{}» не выполнен", name, e);
            synchronized (job) {
                job.errors.add(name + ": " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            }
        }
    }

    @PreDestroy
    void stop() {
        worker.shutdownNow();
    }
}
