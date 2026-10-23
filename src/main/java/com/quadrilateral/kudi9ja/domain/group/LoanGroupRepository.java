package com.quadrilateral.kudi9ja.domain.group;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface LoanGroupRepository extends JpaRepository<LoanGroup, UUID> {

    List<LoanGroup> findAllByOrderByNumberAsc();

    boolean existsByNumber(int number);

    @Query("select coalesce(max(g.number), 0) from LoanGroup g")
    int highestNumber();

    long countByOfficerId(UUID officerId);
}
