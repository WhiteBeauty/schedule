package com.karyakina.schedule.service;

import com.karyakina.schedule.domain.Curatorship;
import com.karyakina.schedule.repository.CuratorshipRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CuratorshipService {

    private final CuratorshipRepository repository;

    public List<Curatorship> findAll() {
        return repository.findAll();
    }

    public List<Curatorship> findByTeacherId(Long teacherId) {
        return repository.findByTeacherId(teacherId);
    }
}
