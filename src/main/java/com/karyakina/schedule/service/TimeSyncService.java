package com.karyakina.schedule.service;

import com.karyakina.schedule.dto.TimeSyncDto;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

@Service
public class TimeSyncService {

    private static final long THRESHOLD_SECONDS = 5;
    private static final DateTimeFormatter FMT = DateTimeFormatter.ISO_INSTANT;

    public TimeSyncDto checkSync(long clientEpochMillis) {
        long serverEpoch = Instant.now().toEpochMilli();
        long diffSeconds = Math.abs(serverEpoch - clientEpochMillis) / 1000;

        String status = diffSeconds <= THRESHOLD_SECONDS ? "SYNCHRONIZED" : "DESYNC";
        String message = status.equals("SYNCHRONIZED")
                ? "Время синхронизировано"
                : "Рассинхронизация: ±" + diffSeconds + " сек";

        return TimeSyncDto.builder()
                .status(status)
                .diffSeconds(diffSeconds)
                .serverTime(Instant.ofEpochMilli(serverEpoch).toString())
                .clientTime(Instant.ofEpochMilli(clientEpochMillis).toString())
                .message(message)
                .build();
    }
}
