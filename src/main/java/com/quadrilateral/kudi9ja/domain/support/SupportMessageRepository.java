package com.quadrilateral.kudi9ja.domain.support;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SupportMessageRepository extends JpaRepository<SupportMessage, UUID> {

    List<SupportMessage> findByConversationIdOrderByCreatedAtAsc(UUID conversationId);

    Optional<SupportMessage> findByIdAndConversationId(UUID id, UUID conversationId);
}
