package com.quadrilateral.kudi9ja.web.controller.admin;

import com.quadrilateral.kudi9ja.common.api.PageResponse;
import com.quadrilateral.kudi9ja.domain.admin.AdminAccessService;
import com.quadrilateral.kudi9ja.domain.support.SupportConversation;
import com.quadrilateral.kudi9ja.domain.support.SupportEvents;
import com.quadrilateral.kudi9ja.domain.support.SupportService;
import com.quadrilateral.kudi9ja.web.controller.SupportFiles;
import com.quadrilateral.kudi9ja.web.dto.SupportDtos;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.core.io.InputStreamResource;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The team's side of help and complaints. Anyone with access to the panel,
 * whatever their role, can read the queue and answer, take, resolve and
 * reopen a conversation.
 */
@RestController
@RequestMapping("/api/v1/admin/support")
@Tag(name = "Admin — help and complaints", description = "Answering customers in real time")
public class AdminSupportController {

    private final SupportService support;
    private final SupportEvents events;
    private final AdminAccessService access;

    public AdminSupportController(SupportService support, SupportEvents events, AdminAccessService access) {
        this.support = support;
        this.events = events;
        this.access = access;
    }

    @GetMapping("/summary")
    @Operation(summary = "Counts for the sidebar and the queue's header")
    public SupportDtos.SupportQueueSummary summary() {
        access.requireCanView();
        return support.summary();
    }

    @GetMapping("/conversations")
    @Operation(summary = "The queue: waiting on the team first, then latest")
    public PageResponse<SupportDtos.SupportConversationView> queue(
            @RequestParam(required = false) SupportConversation.Status status,
            @RequestParam(required = false) SupportConversation.Kind kind,
            @RequestParam(required = false) UUID customerId,
            @RequestParam(required = false) String query,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        access.requireCanView();
        return PageResponse.of(
                support.queue(status, kind, customerId, query, PageRequest.of(page, Math.min(size, 100))),
                view -> view);
    }

    @GetMapping("/conversations/{id}")
    @Operation(summary = "A conversation and its messages. Marks the team's side read.")
    public SupportDtos.SupportThread read(@PathVariable UUID id) {
        access.requireCanView();
        return support.readAsStaff(id);
    }

    @PostMapping("/conversations/{id}/messages")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Reply. The customer is notified.")
    public SupportDtos.SupportMessageView reply(@PathVariable UUID id, @Valid @RequestBody SupportDtos.SupportReply reply) {
        return support.staffPost(access.requireCanView(), id, reply.body(), null);
    }

    @PostMapping(value = "/conversations/{id}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Send the customer a file, with an optional message")
    public SupportDtos.SupportMessageView attach(
            @PathVariable UUID id,
            @RequestPart("file") MultipartFile file,
            @RequestParam(value = "body", required = false) String body) {
        return support.staffPost(access.requireCanView(), id, body, SupportFiles.attachment(file));
    }

    @PostMapping("/conversations/{id}/assign")
    @Operation(summary = "Take it on")
    public SupportDtos.SupportConversationView assign(@PathVariable UUID id) {
        return support.assignToMe(access.requireCanView(), id);
    }

    @PostMapping("/conversations/{id}/resolve")
    @Operation(summary = "Mark resolved, with a note the customer sees")
    public SupportDtos.SupportConversationView resolve(
            @PathVariable UUID id,
            @Valid @RequestBody(required = false) SupportDtos.ResolveSupportConversation request) {
        return support.staffResolve(access.requireCanView(), id, request == null ? null : request.note());
    }

    @PostMapping("/conversations/{id}/reopen")
    @Operation(summary = "Reopen a resolved conversation")
    public SupportDtos.SupportConversationView reopen(@PathVariable UUID id) {
        return support.staffReopen(access.requireCanView(), id);
    }

    @PostMapping("/conversations/{id}/typing")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Tell the customer someone is typing. Stored nowhere.")
    public void typing(@PathVariable UUID id) {
        support.staffTyping(access.requireCanView(), id);
    }

    @GetMapping("/attachments/{messageId}")
    @Operation(summary = "A file from a conversation. Audited.")
    public ResponseEntity<InputStreamResource> file(@PathVariable UUID messageId) {
        return SupportFiles.serve(support.staffAttachment(access.requireCanView(), messageId));
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Server-sent events for the whole queue: message, conversation, typing")
    public SseEmitter stream() {
        access.requireCanView();
        return events.forStaff();
    }
}
