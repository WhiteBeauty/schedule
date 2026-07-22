package com.karyakina.schedule.service;

import com.karyakina.schedule.domain.Notification;
import com.karyakina.schedule.domain.Schedule;
import com.karyakina.schedule.domain.Teacher;
import com.karyakina.schedule.domain.TeacherLoad;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.TextStyle;
import java.util.Locale;

/**
 * Единая точка формирования и рассылки уведомлений о любом действии администратора,
 * влияющем на учебный процесс: создание/удаление пары, перенос времени/аудитории,
 * назначение замены, отмена занятия, изменение нагрузки. Каждое уведомление:
 *  - всегда попадает во внутренний центр уведомлений (счётчик у колокольчика);
 *  - дополнительно дублируется на email, если критично по времени (например, отмена
 *    пары менее чем за 24 часа до её начала).
 * Содержимое формируется по шаблону "что изменилось, когда, у кого, ссылка на запись".
 */
@Service
@RequiredArgsConstructor
public class ScheduleChangeNotifier {

    private final NotificationService notificationService;
    private static final String SCHEDULE_LINK = "/schedule";

    public void pairCreated(Schedule schedule, String adminName) {
        TeacherLoad load = schedule.getTeacherLoad();
        Teacher teacher = load.getTeacher();
        String message = String.format(
                "Администратор %s добавил вам новую пару «%s» с группой %s: %s, %s, ауд. %s.",
                adminName, load.getDiscipline().getName(), load.getGroup().getName(),
                dayLabel(schedule.getDayOfWeek()), timeLabel(schedule), schedule.getClassroom());

        notificationService.notifyTeacher(teacher, Notification.Type.SCHEDULE_CHANGED,
                "Новая пара в расписании: " + load.getDiscipline().getName(),
                message, null, SCHEDULE_LINK, false);
    }

    /** before/after — снимки ДО и ПОСЛЕ изменения (день/время/аудитория). */
    public void pairUpdated(Schedule before, Schedule after, String adminName) {
        TeacherLoad load = after.getTeacherLoad();
        Teacher teacher = load.getTeacher();

        boolean timeChanged = !before.getDayOfWeek().equals(after.getDayOfWeek())
                || !before.getStartTime().equals(after.getStartTime())
                || !before.getEndTime().equals(after.getEndTime());
        boolean roomChanged = !safeEquals(before.getClassroom(), after.getClassroom());

        if (!timeChanged && !roomChanged) return; // нечего сообщать

        String message = String.format(
                "Администратор %s перенёс вашу пару «%s» у группы %s с %s %s (ауд. %s) на %s %s (ауд. %s).",
                adminName, load.getDiscipline().getName(), load.getGroup().getName(),
                dayLabel(before.getDayOfWeek()), timeLabel(before), before.getClassroom(),
                dayLabel(after.getDayOfWeek()), timeLabel(after), after.getClassroom());

        boolean critical = isWithin24Hours(before);

        notificationService.notifyTeacher(teacher, Notification.Type.SCHEDULE_CHANGED,
                "Перенос пары: " + load.getDiscipline().getName(),
                message, null, SCHEDULE_LINK, critical);
    }

    public void pairDeleted(Schedule schedule, String adminName) {
        TeacherLoad load = schedule.getTeacherLoad();
        Teacher teacher = load.getTeacher();
        String message = String.format(
                "Администратор %s отменил вашу пару «%s» у группы %s (%s, %s, ауд. %s).",
                adminName, load.getDiscipline().getName(), load.getGroup().getName(),
                dayLabel(schedule.getDayOfWeek()), timeLabel(schedule), schedule.getClassroom());

        boolean critical = isWithin24Hours(schedule);

        notificationService.notifyTeacher(teacher, Notification.Type.SCHEDULE_CHANGED,
                "Отмена пары: " + load.getDiscipline().getName(),
                message, null, SCHEDULE_LINK, critical);
    }

    public void loadChanged(TeacherLoad load, int oldReadHours, int newReadHours, String adminName) {
        if (oldReadHours == newReadHours) return;
        String message = String.format(
                "Администратор %s скорректировал фактические часы по дисциплине «%s» (группа %s): было %d ч, стало %d ч.",
                adminName, load.getDiscipline().getName(), load.getGroup().getName(), oldReadHours, newReadHours);

        notificationService.notifyTeacher(load.getTeacher(), Notification.Type.LOAD_CHANGED,
                "Корректировка часов: " + load.getDiscipline().getName(),
                message, null, "/time-sync", false);
    }

    // ==================== Вспомогательное ====================

    private boolean safeEquals(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private String dayLabel(DayOfWeek day) {
        return day.getDisplayName(TextStyle.FULL, new Locale("ru"));
    }

    private String timeLabel(Schedule s) {
        return s.getStartTime() + "–" + s.getEndTime();
    }

    /**
     * true, если ближайшее по расписанию занятие (для этого дня недели, начиная с
     * сегодняшнего дня) наступит менее чем через 24 часа — критично для email-канала.
     */
    private boolean isWithin24Hours(Schedule schedule) {
        LocalDateTime next = nextOccurrence(schedule);
        if (next == null) return false;
        return LocalDateTime.now().until(next, java.time.temporal.ChronoUnit.HOURS) < 24;
    }

    private LocalDateTime nextOccurrence(Schedule schedule) {
        if (schedule.getDayOfWeek() == null || schedule.getStartTime() == null) return null;
        LocalDate today = LocalDate.now();
        int daysUntil = (schedule.getDayOfWeek().getValue() - today.getDayOfWeek().getValue() + 7) % 7;
        LocalDate candidate = today.plusDays(daysUntil);
        LocalDateTime result = candidate.atTime(schedule.getStartTime());
        if (daysUntil == 0 && result.isBefore(LocalDateTime.now())) {
            result = result.plusDays(7); // сегодняшнее время уже прошло — ближайшее через неделю
        }
        return result;
    }
}
