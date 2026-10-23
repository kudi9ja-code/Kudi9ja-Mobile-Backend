package com.quadrilateral.kudi9ja.domain.support;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One message in a conversation: text, a file, or both. Never edited and
 * never deleted — a complaint's record is what was said, when.
 */
@Entity
@Table(
        name = "support_message",
        indexes = @Index(name = "ix_support_message_conversation", columnList = "conversation_id, created_at"))
@Getter
@Setter
@NoArgsConstructor
public class SupportMessage {

    public enum Author {
        CUSTOMER,
        STAFF,
        /** Opened, assigned, resolved: what happened, said by nobody in particular. */
        SYSTEM
    }

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "conversation_id", nullable = false, updatable = false)
    private UUID conversationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "author", nullable = false, length = 16, updatable = false)
    private Author author;

    @Column(name = "author_id", updatable = false)
    private UUID authorId;

    /** As shown in the thread: the customer's name, or "Ada from Kudi9ja". */
    @Column(name = "author_name", nullable = false, length = 160, updatable = false)
    private String authorName;

    @Column(name = "body", length = 4000, updatable = false)
    private String body;

    @Column(name = "attachment_key", length = 400, updatable = false)
    private String attachmentKey;

    @Column(name = "attachment_name", length = 200, updatable = false)
    private String attachmentName;

    @Column(name = "attachment_type", length = 120, updatable = false)
    private String attachmentType;

    @Column(name = "attachment_size", updatable = false)
    private Long attachmentSize;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public boolean hasAttachment() {
        return attachmentKey != null;
    }
}
