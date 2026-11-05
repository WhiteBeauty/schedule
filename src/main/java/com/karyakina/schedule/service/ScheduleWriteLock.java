package com.karyakina.schedule.service;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

// пересчёт часов и автосписание по одному иначе пересчёт видит наполовину засчитанные пары
// или ставит доп пары в те же места что и соседний пересчёт
@Component
public class ScheduleWriteLock {

    private static final long WAIT_MINUTES = 3;

    private final ReentrantLock lock = new ReentrantLock(true);

    // внутри транзакции отпускаем только после коммита чтобы следующий увидел наши пары
    public void holdUntilCommit() {
        acquire();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    lock.unlock();
                }
            });
        } else {
            lock.unlock();
        }
    }

    public void runExclusive(Runnable action) {
        acquire();
        try {
            action.run();
        } finally {
            lock.unlock();
        }
    }

    private void acquire() {
        try {
            if (!lock.tryLock(WAIT_MINUTES, TimeUnit.MINUTES)) {
                throw new IllegalStateException("Сейчас пересчитываются часы или засчитываются прошедшие пары, повторите через минуту");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Ожидание пересчёта часов прервано");
        }
    }
}
