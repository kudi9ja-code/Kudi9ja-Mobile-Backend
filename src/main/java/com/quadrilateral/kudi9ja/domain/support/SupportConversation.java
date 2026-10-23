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
 * A conversation between a customer and the Kudi9ja team: a question, or a
 * complaint with the clock the Terms promise.
 *
 * <p>The customer's name and reference are copied here when it is opened, so
 * the queue reads without a join and still reads after the account closes.
 * Unread counts are kept on both sides, so neither the sidebar badge nor the
 * queue has to count messages to know.
 */
@Entity
@Table(
        name = "support_conversation",
        indexes = {
                @Index(name = "ix_support_conversation_user", columnList = "user_id, last_message_at"),
                @Index(name = "ix_support_conversation_queue", columnList = "status, last_message_at"),
                @Index(name = "ux_support_conversation_reference", columnList = "reference", unique = true)
        })
@Getter
@Setter
@NoArgsConstructor
public class SupportConversation {

    public enum Kind {
        COMPLAINT("Complaint"),
        ENQUIRY("Question");

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public enum Status {
        /** Waiting on the team. */
        OPEN("Open"),
        /** The team answered and is waiting on the customer. */
        AWAITING_CUSTOMER("Awaiting your reply"),
        RESOLVED("Resolved");

        private final String label;

        Status(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "customer_name", nullable = false, length = 160)
    private String customerName;

    @Column(name = "customer_ref", nullable = false, length = 40)
    private String customerRef;

    /** "CMP-7Q4KX2" or "HLP-…": what the customer quotes on the phone. */
    @Column(name = "reference", nullable = false, length = 20, updatable = false)
    private String reference;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 16)
    private Kind kind;

    @Column(name = "category", nullable = false, length = 40)
    private String category;

    @Column(name = "subject", nullable = false, length = 160)
    private String subject;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private Status status = Status.OPEN;

    @Column(name = "assigned_to_id")
    private UUID assignedToId;

    @Column(name = "assigned_to_name", length = 160)
    private String assignedToName;

    @Column(name = "last_message_at", nullable = false)
    private Instant lastMessageAt;

    @Column(name = "last_message_preview", length = 200)
    private String lastMessagePreview;

    @Enumerated(EnumType.STRING)
    @Column(name = "last_message_by", length = 16)
    private SupportMessage.Author lastMessageBy;

    @Column(name = "customer_unread", nullable = false)
    private int customerUnread;

    @Column(name = "staff_unread", nullable = false)
    private int staffUnread;

    /** For a complaint: when the first reply is owed. */
    @Column(name = "acknowledge_by")
    private Instant acknowledgeBy;

    @Column(name = "acknowledged_at")
    private Instant acknowledgedAt;

    /** For a complaint: when it must be resolved. */
    @Column(name = "resolve_by")
    private Instant resolveBy;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "resolved_by", length = 200)
    private String resolvedBy;

    @Column(name = "resolution_note", length = 1000)
    private String resolutionNote;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public boolean isResolved() {
        return status == Status.RESOLVED;
    }

    /** A complaint past either of its promised times and still not resolved. */
    public boolean isOverdue(Instant now) {
        if (isResolved()) {
            return false;
        }
        boolean unacknowledged = acknowledgedAt == null && acknowledgeBy != null && now.isAfter(acknowledgeBy);
        boolean unresolved = resolveBy != null && now.isAfter(resolveBy);
        return unacknowledged || unresolved;
    }
}
