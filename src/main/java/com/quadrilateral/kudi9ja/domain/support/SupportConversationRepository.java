package com.quadrilateral.kudi9ja.domain.support;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SupportConversationRepository extends JpaRepository<SupportConversation, UUID> {

    List<SupportConversation> findByUserIdOrderByLastMessageAtDesc(UUID userId);

    Optional<SupportConversation> findByIdAndUserId(UUID id, UUID userId);

    boolean existsByReference(String reference);

    @Query("select coalesce(sum(c.customerUnread), 0) from SupportConversation c where c.userId = :userId")
    long customerUnread(@Param("userId") UUID userId);

    /**
     * The team's queue. Waiting on the team first, then by the latest
     * message, so what needs an answer is at the top.
     */
    @Query("""
            select c from SupportConversation c
            where (:status is null or c.status = :status)
              and (:kind is null or c.kind = :kind)
              and (:userId is null or c.userId = :userId)
              and (:query = '' or lower(c.customerName) like lower(concat('%', :query, '%'))
                   or lower(c.customerRef) like lower(concat('%', :query, '%'))
                   or lower(c.reference) like lower(concat('%', :query, '%'))
                   or lower(c.subject) like lower(concat('%', :query, '%')))
            order by case when c.status = com.quadrilateral.kudi9ja.domain.support.SupportConversation.Status.OPEN then 0 else 1 end,
                     c.lastMessageAt desc
            """)
    Page<SupportConversation> queue(
            @Param("status") SupportConversation.Status status,
            @Param("kind") SupportConversation.Kind kind,
            @Param("userId") UUID userId,
            @Param("query") String query,
            Pageable pageable);

    long countByStatus(SupportConversation.Status status);

    long countByStatusNotAndAssignedToIdIsNull(SupportConversation.Status status);

    @Query("select coalesce(sum(c.staffUnread), 0) from SupportConversation c")
    long staffUnread();

    List<SupportConversation> findByStatusNotAndKind(SupportConversation.Status status, SupportConversation.Kind kind);
}
