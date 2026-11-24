package com.quadrilateral.kudi9ja.domain.credit;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CreditCheckRepository extends JpaRepository<CreditCheck, UUID> {

    List<CreditCheck> findTop20ByUserIdOrderByCheckedAtDesc(UUID userId);
}
