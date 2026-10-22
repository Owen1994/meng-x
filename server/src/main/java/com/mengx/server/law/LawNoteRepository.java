package com.mengx.server.law;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LawNoteRepository extends JpaRepository<LawNoteEntity, Long> {

    List<LawNoteEntity> findByContentHashIn(Collection<String> hashes);

    long countBySubject(String subject);
}
