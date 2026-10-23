package com.quadrilateral.kudi9ja.domain.support;

import com.quadrilateral.kudi9ja.common.error.ApiException;
import com.quadrilateral.kudi9ja.common.error.ErrorCode;
import com.quadrilateral.kudi9ja.config.Kudi9jaProperties;
import com.quadrilateral.kudi9ja.domain.admin.AdminUser;
import com.quadrilateral.kudi9ja.domain.audit.AuditCategory;
import com.quadrilateral.kudi9ja.domain.audit.AuditService;
import com.quadrilateral.kudi9ja.domain.notification.NotificationService;
import com.quadrilateral.kudi9ja.domain.notification.NotifyKind;
import com.quadrilateral.kudi9ja.domain.user.User;
import com.quadrilateral.kudi9ja.domain.user.UserRepository;
import com.quadrilateral.kudi9ja.integration.storage.ReceiptStorage;
import com.quadrilateral.kudi9ja.web.dto.SupportDtos;
import java.io.InputStream;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Help and complaints.
 *
 * <p>A customer opens a conversation — a question, or a complaint — and the
 * team answers in it. Every message is kept as written. A complaint is given
 * the times the Terms promise when it is opened (acknowledged within the
 * hours, resolved within the days under {@code kudi9ja.compliance}), and is
 * logged for compliance when it arrives and when it is resolved.
 *
 * <p>New messages are pushed to open pages by {@link SupportEvents}; a reply
 * from the team is also a notification, which reaches the customer's phone.
 */
@Service
public class SupportService {

    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("EEE d MMM, h:mm a", Locale.ENGLISH).withZone(ZoneId.of("Africa/Lagos"));

    private final SupportConversationRepository conversations;
    private final SupportMessageRepository messages;
    private final UserRepository users;
    private final NotificationService notifications;
    private final AuditService audit;
    private final ReceiptStorage storage;
    private final SupportEvents events;
    private final Kudi9jaProperties properties;

    public SupportService(
            SupportConversationRepository conversations,
            SupportMessageRepository messages,
            UserRepository users,
            NotificationService notifications,
            AuditService audit,
            ReceiptStorage storage,
            SupportEvents events,
            Kudi9jaProperties properties) {
        this.conversations = conversations;
        this.messages = messages;
        this.users = users;
        this.notifications = notifications;
        this.audit = audit;
        this.storage = storage;
        this.events = events;
        this.properties = properties;
    }

    /** A file attached to a message. */
    public record Attachment(String filename, String contentType, byte[] content) {
    }

    /** A file on its way out: what to call it and how to read it. */
    public record AttachmentContent(String filename, String contentType, InputStream content) {
    }

    // ── The customer ──────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<SupportDtos.SupportConversationView> mine(UUID userId) {
        return conversations.findByUserIdOrderByLastMessageAtDesc(userId).stream()
                .map(c -> view(c, false))
                .toList();
    }

    @Transactional(readOnly = true)
    public long unreadFor(UUID userId) {
        return conversations.customerUnread(userId);
    }

    @Transactional
    public SupportDtos.SupportThread open(UUID userId, SupportDtos.StartSupportConversation request) {
        User user = users.findById(userId).orElseThrow(() -> ApiException.notFound("Your account"));
        if (!SupportDtos.CATEGORIES.contains(request.category())) {
            throw ApiException.validation("Choose one of the topics shown.");
        }
        Instant now = Instant.now();
        boolean complaint = request.kind() == SupportConversation.Kind.COMPLAINT;

        SupportConversation c = new SupportConversation();
        c.setId(UUID.randomUUID());
        c.setUserId(userId);
        c.setCustomerName(user.getFullName());
        c.setCustomerRef(user.getCustomerRef());
        c.setReference(newReference(complaint ? "CMP-" : "HLP-"));
        c.setKind(request.kind());
        c.setCategory(request.category());
        c.setSubject(request.subject().trim());
        c.setStatus(SupportConversation.Status.OPEN);
        c.setCreatedAt(now);
        c.setLastMessageAt(now);
        if (complaint) {
            Kudi9jaProperties.Compliance policy = properties.compliance();
            c.setAcknowledgeBy(now.plus(Duration.ofHours(policy.complaintAcknowledgementHours())));
            c.setResolveBy(now.plus(Duration.ofDays(policy.complaintResolutionDays())));
        }
        conversations.save(c);

        SupportMessage first = append(c, SupportMessage.Author.CUSTOMER, userId, user.getFullName(),
                request.message().trim(), null);
        SupportMessage receipt = append(c, SupportMessage.Author.SYSTEM, null, "Kudi9ja", complaint
                ? "We have your complaint, reference " + c.getReference() + ". Someone from the team will reply by "
                        + WHEN.format(c.getAcknowledgeBy()) + ", and we aim to resolve it by "
                        + WHEN.format(c.getResolveBy()) + "."
                : "Thanks — someone from the team will reply here. Your reference is " + c.getReference() + ".",
                null);
        // The receipt is the customer's own confirmation; it does not count as
        // unread, and the queue should show what they actually said.
        c.setCustomerUnread(0);
        c.setLastMessageBy(SupportMessage.Author.CUSTOMER);
        c.setLastMessagePreview(preview(first.getBody()));
        c.setStaffUnread(1);
        conversations.save(c);

        if (complaint) {
            audit.recordSystem(
                    AuditCategory.COMPLIANCE,
                    "Complaint received",
                    c.getCustomerName() + " (" + c.getCustomerRef() + ") raised complaint " + c.getReference()
                            + " about " + c.getCategory().toLowerCase(Locale.ROOT) + ": \"" + c.getSubject()
                            + "\". Acknowledge by " + WHEN.format(c.getAcknowledgeBy()) + ", resolve by "
                            + WHEN.format(c.getResolveBy()) + ".");
        }
        push(c, first);
        push(c, receipt);
        return thread(c, false);
    }

    /** Reading a conversation marks the customer's side read. */
    @Transactional
    public SupportDtos.SupportThread readMine(UUID userId, UUID conversationId) {
        SupportConversation c = mineOrThrow(userId, conversationId);
        if (c.getCustomerUnread() > 0) {
            c.setCustomerUnread(0);
            conversations.save(c);
            events.publish(c.getUserId(), "conversation", view(c, false), view(c, true));
        }
        return thread(c, false);
    }

    @Transactional
    public SupportDtos.SupportMessageView customerPost(UUID userId, UUID conversationId, String body, Attachment file) {
        SupportConversation c = mineOrThrow(userId, conversationId);
        User user = users.findById(userId).orElseThrow(() -> ApiException.notFound("Your account"));
        if (c.isResolved()) {
            // Writing again reopens it: a customer who says "this is still
            // happening" has not been helped yet.
            reopen(c);
            push(c, append(c, SupportMessage.Author.SYSTEM, null, "Kudi9ja",
                    "Reopened by " + firstName(c.getCustomerName()) + ".", null));
        }
        SupportMessage m = append(c, SupportMessage.Author.CUSTOMER, userId, user.getFullName(), clean(body), file);
        c.setStatus(SupportConversation.Status.OPEN);
        c.setStaffUnread(c.getStaffUnread() + 1);
        c.setCustomerUnread(0);
        conversations.save(c);
        push(c, m);
        return messageView(m, false, userId, c);
    }

    @Transactional
    public SupportDtos.SupportConversationView customerResolve(UUID userId, UUID conversationId) {
        SupportConversation c = mineOrThrow(userId, conversationId);
        if (!c.isResolved()) {
            resolve(c, c.getCustomerName() + " (customer)", "Marked as sorted by the customer.");
        }
        return view(c, false);
    }

    @Transactional(readOnly = true)
    public AttachmentContent customerAttachment(UUID userId, UUID messageId) {
        SupportMessage m = messages.findById(messageId).orElseThrow(() -> ApiException.notFound("That file"));
        mineOrThrow(userId, m.getConversationId());
        if (!m.hasAttachment()) {
            throw ApiException.notFound("That file");
        }
        return new AttachmentContent(m.getAttachmentName(), m.getAttachmentType(), storage.open(m.getAttachmentKey()));
    }

    public void customerTyping(UUID userId, UUID conversationId) {
        SupportConversation c = mineOrThrow(userId, conversationId);
        events.publish(c.getUserId(), "typing", null, new SupportDtos.SupportTypingEvent(
                c.getId(), SupportMessage.Author.CUSTOMER, firstName(c.getCustomerName())));
    }

    // ── The team ──────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Page<SupportDtos.SupportConversationView> queue(
            SupportConversation.Status status, SupportConversation.Kind kind, UUID customerId, String query,
            Pageable pageable) {
        String q = query == null ? "" : query.trim();
        return conversations.queue(status, kind, customerId, q, pageable).map(c -> view(c, true));
    }

    @Transactional(readOnly = true)
    public SupportDtos.SupportQueueSummary summary() {
        Instant now = Instant.now();
        long overdue = conversations
                .findByStatusNotAndKind(SupportConversation.Status.RESOLVED, SupportConversation.Kind.COMPLAINT)
                .stream()
                .filter(c -> c.isOverdue(now))
                .count();
        return new SupportDtos.SupportQueueSummary(
                conversations.countByStatus(SupportConversation.Status.OPEN),
                conversations.countByStatus(SupportConversation.Status.AWAITING_CUSTOMER),
                conversations.countByStatusNotAndAssignedToIdIsNull(SupportConversation.Status.RESOLVED),
                overdue,
                conversations.staffUnread());
    }

    @Transactional
    public SupportDtos.SupportThread readAsStaff(UUID conversationId) {
        SupportConversation c = find(conversationId);
        if (c.getStaffUnread() > 0) {
            c.setStaffUnread(0);
            conversations.save(c);
            events.publish(c.getUserId(), "conversation", null, view(c, true));
        }
        return thread(c, true);
    }

    @Transactional
    public SupportDtos.SupportMessageView staffPost(AdminUser admin, UUID conversationId, String body, Attachment file) {
        SupportConversation c = find(conversationId);
        if (c.isResolved()) {
            reopen(c);
        }
        if (c.getAssignedToId() == null) {
            c.setAssignedToId(admin.getUserId());
            c.setAssignedToName(admin.getName());
        }
        Instant now = Instant.now();
        if (c.getAcknowledgedAt() == null) {
            c.setAcknowledgedAt(now);
        }
        SupportMessage m = append(c, SupportMessage.Author.STAFF, admin.getUserId(), staffName(admin), clean(body), file);
        c.setStatus(SupportConversation.Status.AWAITING_CUSTOMER);
        c.setCustomerUnread(c.getCustomerUnread() + 1);
        c.setStaffUnread(0);
        conversations.save(c);

        String preview = m.getBody() == null ? "Sent you a file." : preview(m.getBody());
        notifications.push(c.getUserId(), NotifyKind.SUPPORT, "Kudi9ja replied about " + c.getReference(), preview);
        push(c, m);
        return messageView(m, true, admin.getUserId(), c);
    }

    @Transactional
    public SupportDtos.SupportConversationView assignToMe(AdminUser admin, UUID conversationId) {
        SupportConversation c = find(conversationId);
        c.setAssignedToId(admin.getUserId());
        c.setAssignedToName(admin.getName());
        conversations.save(c);
        push(c, append(c, SupportMessage.Author.SYSTEM, null, "Kudi9ja",
                staffName(admin) + " is looking after this now.", null));
        return view(c, true);
    }

    @Transactional
    public SupportDtos.SupportConversationView staffResolve(AdminUser admin, UUID conversationId, String note) {
        SupportConversation c = find(conversationId);
        if (c.isResolved()) {
            throw new ApiException(ErrorCode.ALREADY_REVIEWED, "That conversation is already resolved.");
        }
        resolve(c, admin.getName() + " <" + admin.getEmail() + ">",
                note == null || note.isBlank() ? null : note.trim());
        notifications.push(c.getUserId(), NotifyKind.SUPPORT, c.getReference() + " resolved",
                c.getKind() == SupportConversation.Kind.COMPLAINT
                        ? "We have marked your complaint resolved. If it is not, reply and it reopens."
                        : "We have marked your question answered. Reply any time if you need more help.");
        return view(c, true);
    }

    @Transactional
    public SupportDtos.SupportConversationView staffReopen(AdminUser admin, UUID conversationId) {
        SupportConversation c = find(conversationId);
        if (c.isResolved()) {
            reopen(c);
            conversations.save(c);
            push(c, append(c, SupportMessage.Author.SYSTEM, null, "Kudi9ja",
                    "Reopened by " + staffName(admin) + ".", null));
        }
        return view(c, true);
    }

    /** A file from a conversation, for the team. Opening one is logged, like any customer record. */
    public AttachmentContent staffAttachment(AdminUser admin, UUID messageId) {
        SupportMessage m = messages.findById(messageId).orElseThrow(() -> ApiException.notFound("That file"));
        if (!m.hasAttachment()) {
            throw ApiException.notFound("That file");
        }
        SupportConversation c = find(m.getConversationId());
        audit.record(
                new AuditService.Actor(admin.getUserId(), admin.getName(), admin.getEmail()),
                AuditCategory.DATA_ACCESS,
                "Support attachment opened",
                admin.getName() + " opened \"" + m.getAttachmentName() + "\" in " + c.getReference() + ".",
                c.getUserId(),
                c.getCustomerRef());
        return new AttachmentContent(m.getAttachmentName(), m.getAttachmentType(), storage.open(m.getAttachmentKey()));
    }

    public void staffTyping(AdminUser admin, UUID conversationId) {
        SupportConversation c = find(conversationId);
        events.publish(c.getUserId(), "typing", new SupportDtos.SupportTypingEvent(
                c.getId(), SupportMessage.Author.STAFF, staffName(admin)), null);
    }

    // ── Plumbing ──────────────────────────────────────────────────────────

    private SupportConversation mineOrThrow(UUID userId, UUID conversationId) {
        return conversations.findByIdAndUserId(conversationId, userId)
                .orElseThrow(() -> ApiException.notFound("That conversation"));
    }

    private SupportConversation find(UUID conversationId) {
        return conversations.findById(conversationId).orElseThrow(() -> ApiException.notFound("That conversation"));
    }

    private void resolve(SupportConversation c, String by, String note) {
        Instant now = Instant.now();
        c.setStatus(SupportConversation.Status.RESOLVED);
        c.setResolvedAt(now);
        c.setResolvedBy(by);
        c.setResolutionNote(note);
        c.setStaffUnread(0);
        conversations.save(c);
        push(c, append(c, SupportMessage.Author.SYSTEM, null, "Kudi9ja",
                note == null ? "Marked as resolved." : "Marked as resolved: " + note, null));
        if (c.getKind() == SupportConversation.Kind.COMPLAINT) {
            boolean late = c.getResolveBy() != null && now.isAfter(c.getResolveBy());
            audit.recordSystem(
                    AuditCategory.COMPLIANCE,
                    "Complaint resolved",
                    "Complaint " + c.getReference() + " for " + c.getCustomerName() + " (" + c.getCustomerRef()
                            + ") was resolved by " + by + (late ? ", after the promised date" : ", on time")
                            + (note == null ? "." : ". Note: " + note));
        }
    }

    private static void reopen(SupportConversation c) {
        c.setStatus(SupportConversation.Status.OPEN);
        c.setResolvedAt(null);
        c.setResolvedBy(null);
        c.setResolutionNote(null);
    }

    private SupportMessage append(
            SupportConversation c, SupportMessage.Author author, UUID authorId, String authorName, String body,
            Attachment file) {
        if ((body == null || body.isBlank()) && file == null) {
            throw ApiException.validation("Write a message or attach a file.");
        }
        SupportMessage m = new SupportMessage();
        m.setId(UUID.randomUUID());
        m.setConversationId(c.getId());
        m.setAuthor(author);
        m.setAuthorId(authorId);
        m.setAuthorName(authorName.length() > 160 ? authorName.substring(0, 160) : authorName);
        m.setBody(body == null || body.isBlank() ? null : body);
        m.setCreatedAt(Instant.now());
        if (file != null) {
            storeAttachment(c, m, file);
        }
        messages.save(m);

        c.setLastMessageAt(m.getCreatedAt());
        c.setLastMessageBy(author);
        c.setLastMessagePreview(m.getBody() == null ? "📎 " + m.getAttachmentName() : preview(m.getBody()));
        return m;
    }

    private void storeAttachment(SupportConversation c, SupportMessage m, Attachment file) {
        Kudi9jaProperties.Storage config = properties.storage();
        if (file.content() == null || file.content().length == 0) {
            throw ApiException.validation("That file is empty.");
        }
        if (file.content().length > config.maxReceiptBytes()) {
            throw ApiException.validation("That file is too large. Attach one under "
                    + (config.maxReceiptBytes() / (1024 * 1024)) + "MB.");
        }
        String type = file.contentType() == null ? "" : file.contentType().toLowerCase(Locale.ROOT);
        if (config.allowedReceiptTypes().stream().noneMatch(type::startsWith)) {
            throw ApiException.validation("Attach a photo, a PDF, or a Word or Excel document.");
        }
        String name = file.filename() == null || file.filename().isBlank() ? "attachment" : file.filename().trim();
        if (name.length() > 200) {
            name = name.substring(name.length() - 200);
        }
        m.setAttachmentKey(storage.store(c.getCustomerRef(), name, file.contentType(), file.content()));
        m.setAttachmentName(name);
        m.setAttachmentType(file.contentType());
        m.setAttachmentSize((long) file.content().length);
    }

    /** Sends a message, and the conversation it changed, to both sides. */
    private void push(SupportConversation c, SupportMessage m) {
        events.publish(
                c.getUserId(),
                "message",
                new SupportDtos.SupportMessageEvent(view(c, false), messageView(m, false, c.getUserId(), c)),
                new SupportDtos.SupportMessageEvent(view(c, true), messageView(m, true, null, c)));
    }

    private SupportDtos.SupportThread thread(SupportConversation c, boolean forStaff) {
        UUID me = forStaff ? null : c.getUserId();
        List<SupportDtos.SupportMessageView> list = messages.findByConversationIdOrderByCreatedAtAsc(c.getId()).stream()
                .map(m -> messageView(m, forStaff, me, c))
                .toList();
        return new SupportDtos.SupportThread(view(c, forStaff), list);
    }

    private SupportDtos.SupportMessageView messageView(SupportMessage m, boolean forStaff, UUID me, SupportConversation c) {
        String url = !m.hasAttachment()
                ? null
                : forStaff
                        ? "/api/v1/admin/support/attachments/" + m.getId()
                        : "/api/v1/support/attachments/" + m.getId();
        boolean mine = forStaff
                ? m.getAuthor() == SupportMessage.Author.STAFF && (me == null || me.equals(m.getAuthorId()))
                : m.getAuthor() == SupportMessage.Author.CUSTOMER;
        return new SupportDtos.SupportMessageView(
                m.getId(), c.getId(), m.getAuthor(), m.getAuthorName(), mine, m.getBody(),
                m.getAttachmentName(), m.getAttachmentType(), m.getAttachmentSize(), url, m.getCreatedAt());
    }

    private static SupportDtos.SupportConversationView view(SupportConversation c, boolean forStaff) {
        return new SupportDtos.SupportConversationView(
                c.getId(),
                c.getReference(),
                c.getKind(),
                c.getKind().label(),
                c.getCategory(),
                c.getSubject(),
                c.getStatus(),
                forStaff && c.getStatus() == SupportConversation.Status.AWAITING_CUSTOMER
                        ? "Awaiting customer"
                        : c.getStatus().label(),
                c.getAssignedToName(),
                c.getLastMessageAt(),
                c.getLastMessagePreview(),
                c.getLastMessageBy(),
                forStaff ? c.getStaffUnread() : c.getCustomerUnread(),
                c.getAcknowledgeBy(),
                c.getAcknowledgedAt(),
                c.getResolveBy(),
                c.getResolvedAt(),
                c.getResolutionNote(),
                c.isOverdue(Instant.now()),
                c.getCreatedAt(),
                forStaff ? c.getUserId() : null,
                forStaff ? c.getCustomerName() : null,
                forStaff ? c.getCustomerRef() : null,
                forStaff ? c.getAssignedToId() : null,
                forStaff ? c.getResolvedBy() : null);
    }

    private String newReference(String prefix) {
        for (int attempt = 0; attempt < 10; attempt++) {
            StringBuilder ref = new StringBuilder(prefix);
            for (int i = 0; i < 6; i++) {
                ref.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
            }
            if (!conversations.existsByReference(ref.toString())) {
                return ref.toString();
            }
        }
        throw new ApiException(ErrorCode.INTERNAL, "We could not open that conversation. Please try again.");
    }

    /** "Ada from Kudi9ja": a person, without their surname. */
    private static String staffName(AdminUser admin) {
        return firstName(admin.getName()) + " from Kudi9ja";
    }

    private static String firstName(String name) {
        if (name == null || name.isBlank()) {
            return "Someone";
        }
        return name.trim().split("\\s+")[0];
    }

    private static String clean(String body) {
        if (body == null) {
            return null;
        }
        String trimmed = body.trim();
        if (trimmed.length() > 4000) {
            throw ApiException.validation("Keep a message under 4,000 characters.");
        }
        return trimmed;
    }

    private static String preview(String body) {
        String flat = body.replaceAll("\\s+", " ").trim();
        return flat.length() <= 140 ? flat : flat.substring(0, 137) + "…";
    }
}
