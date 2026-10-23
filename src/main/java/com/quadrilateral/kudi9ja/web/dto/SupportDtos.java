package com.quadrilateral.kudi9ja.web.dto;

import com.quadrilateral.kudi9ja.domain.support.SupportConversation;
import com.quadrilateral.kudi9ja.domain.support.SupportMessage;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Help and complaints: a customer's conversations with the Kudi9ja team. */
public final class SupportDtos {

    private SupportDtos() {
    }

    /** The topics a customer can choose from. Free text is not offered: the queue sorts by these. */
    public static final List<String> CATEGORIES = List.of(
            "Paying in", "Withdrawals", "Loans", "Savings", "Ajo circles", "My account", "Something else");

    public record StartSupportConversation(
            @NotNull(message = "Say whether this is a complaint or a question.")
            SupportConversation.Kind kind,

            @NotBlank(message = "Choose a topic.")
            @Size(max = 40, message = "Keep the topic under 40 characters.")
            String category,

            @NotBlank(message = "Give it a short title.")
            @Size(min = 4, max = 160, message = "Keep the title between 4 and 160 characters.")
            String subject,

            @NotBlank(message = "Tell us what happened.")
            @Size(max = 4000, message = "Keep a message under 4,000 characters.")
            String message) {
    }

    public record SupportReply(
            @NotBlank(message = "Write a message.")
            @Size(max = 4000, message = "Keep a message under 4,000 characters.")
            String body) {
    }

    public record ResolveSupportConversation(
            @Size(max = 1000, message = "Keep the note under 1,000 characters.")
            String note) {
    }

    public record SupportMessageView(
            UUID id,
            UUID conversationId,
            SupportMessage.Author author,
            String authorName,
            boolean mine,
            String body,
            String attachmentName,
            String attachmentType,
            Long attachmentSize,
            String attachmentUrl,
            Instant createdAt) {
    }

    public record SupportConversationView(
            UUID id,
            String reference,
            SupportConversation.Kind kind,
            String kindLabel,
            String category,
            String subject,
            SupportConversation.Status status,
            String statusLabel,
            String assignedToName,
            Instant lastMessageAt,
            String lastMessagePreview,
            SupportMessage.Author lastMessageBy,
            int unread,
            Instant acknowledgeBy,
            Instant acknowledgedAt,
            Instant resolveBy,
            Instant resolvedAt,
            String resolutionNote,
            boolean overdue,
            Instant createdAt,
            /* The panel's fields; absent on the customer's view. */
            UUID customerId,
            String customerName,
            String customerRef,
            UUID assignedToId,
            String resolvedBy) {
    }

    public record SupportThread(SupportConversationView conversation, List<SupportMessageView> messages) {
    }

    /** One pushed message, with the conversation as it now stands. */
    public record SupportMessageEvent(SupportConversationView conversation, SupportMessageView message) {
    }

    public record SupportTypingEvent(UUID conversationId, SupportMessage.Author who, String name) {
    }

    public record SupportUnread(long unread) {
    }

    public record SupportQueueSummary(long open, long awaitingCustomer, long unassigned, long overdue, long unread) {
    }

    public record SupportTopics(List<String> categories) {
    }
}
