package com.quadrilateral.kudi9ja.web.controller;

import com.quadrilateral.kudi9ja.domain.support.SupportEvents;
import com.quadrilateral.kudi9ja.domain.support.SupportService;
import com.quadrilateral.kudi9ja.security.auth.CurrentUser;
import com.quadrilateral.kudi9ja.web.dto.SupportDtos;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.core.io.InputStreamResource;
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

/** Help and complaints, from the customer's side: conversations with the Kudi9ja team. */
@RestController
@RequestMapping("/api/v1/support")
@Tag(name = "Help and complaints", description = "Live conversations with the Kudi9ja team")
public class SupportController {

    private final SupportService support;
    private final SupportEvents events;
    private final CurrentUser currentUser;

    public SupportController(SupportService support, SupportEvents events, CurrentUser currentUser) {
        this.support = support;
        this.events = events;
        this.currentUser = currentUser;
    }

    @GetMapping("/topics")
    @Operation(summary = "The topics a conversation can be about")
    public SupportDtos.SupportTopics topics() {
        return new SupportDtos.SupportTopics(SupportDtos.CATEGORIES);
    }

    @GetMapping("/conversations")
    @Operation(summary = "Your conversations, latest first")
    public List<SupportDtos.SupportConversationView> mine() {
        return support.mine(currentUser.requireId());
    }

    @GetMapping("/unread")
    @Operation(summary = "How many replies you have not read yet")
    public SupportDtos.SupportUnread unread() {
        return new SupportDtos.SupportUnread(support.unreadFor(currentUser.requireId()));
    }

    @PostMapping("/conversations")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Ask a question or make a complaint")
    public SupportDtos.SupportThread open(@Valid @RequestBody SupportDtos.StartSupportConversation request) {
        return support.open(currentUser.requireId(), request);
    }

    @GetMapping("/conversations/{id}")
    @Operation(summary = "A conversation and every message in it. Marks it read.")
    public SupportDtos.SupportThread read(@PathVariable UUID id) {
        return support.readMine(currentUser.requireId(), id);
    }

    @PostMapping("/conversations/{id}/messages")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Write in a conversation. Writing in a resolved one reopens it.")
    public SupportDtos.SupportMessageView post(@PathVariable UUID id, @Valid @RequestBody SupportDtos.SupportReply reply) {
        return support.customerPost(currentUser.requireId(), id, reply.body(), null);
    }

    @PostMapping(value = "/conversations/{id}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Send a photo or document, with an optional message")
    public SupportDtos.SupportMessageView attach(
            @PathVariable UUID id,
            @RequestPart("file") MultipartFile file,
            @RequestParam(value = "body", required = false) String body) {
        return support.customerPost(currentUser.requireId(), id, body, SupportFiles.attachment(file));
    }

    @PostMapping("/conversations/{id}/resolve")
    @Operation(summary = "Mark it sorted")
    public SupportDtos.SupportConversationView resolve(@PathVariable UUID id) {
        return support.customerResolve(currentUser.requireId(), id);
    }

    @PostMapping("/conversations/{id}/typing")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Tell the team you are typing. Stored nowhere.")
    public void typing(@PathVariable UUID id) {
        support.customerTyping(currentUser.requireId(), id);
    }

    @GetMapping("/attachments/{messageId}")
    @Operation(summary = "A file from one of your conversations")
    public ResponseEntity<InputStreamResource> file(@PathVariable UUID messageId) {
        return SupportFiles.serve(support.customerAttachment(currentUser.requireId(), messageId));
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Server-sent events: message, conversation, typing")
    public SseEmitter stream() {
        return events.forCustomer(currentUser.requireId());
    }
}
