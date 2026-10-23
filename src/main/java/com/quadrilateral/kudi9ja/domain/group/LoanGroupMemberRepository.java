package com.quadrilateral.kudi9ja.domain.group;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LoanGroupMemberRepository extends JpaRepository<LoanGroupMember, UUID> {

    List<LoanGroupMember> findByGroupIdOrderByAddedAtAsc(UUID groupId);

    List<LoanGroupMember> findByGroupIdIn(Collection<UUID> groupIds);

    List<LoanGroupMember> findByUserId(UUID userId);

    List<LoanGroupMember> findByImportedLoanId(UUID importedLoanId);

    Optional<LoanGroupMember> findByGroupIdAndUserId(UUID groupId, UUID userId);

    long countByGroupId(UUID groupId);
}
