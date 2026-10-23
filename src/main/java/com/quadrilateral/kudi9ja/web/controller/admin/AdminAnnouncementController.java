package com.quadrilateral.kudi9ja.web.controller.admin;

import com.quadrilateral.kudi9ja.domain.admin.AdminAccessService;
import com.quadrilateral.kudi9ja.domain.admin.AdminUser;
import com.quadrilateral.kudi9ja.domain.audit.AuditCategory;
import com.quadrilateral.kudi9ja.domain.audit.AuditService;
import com.quadrilateral.kudi9ja.domain.notification.NotificationService;
import com.quadrilateral.kudi9ja.web.dto.AnnouncementDtos;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A push notification to every customer's phone, with an optional picture.
 *
 * <p>Built to need no app release: it goes through the same Firebase channel
 * the installed app already listens on. The picture is drawn by the phone
 * itself, so it shows while the app is closed or in the background; a phone
 * with the app open at that moment shows the text alone.
 *
 * <p>Limited to owners and administrators, because it reaches everybody at
 * once and cannot be taken back. Every send is audited.
 */
@RestController
@RequestMapping("/api/v1/admin/announcements")
@Tag(name = "Admin — announcements", description = "A push notification to every customer")
public class AdminAnnouncementController {

    private final NotificationService notifications;
    private final AdminAccessService access;
    private final AuditService audit;

    public AdminAnnouncementController(
            NotificationService notifications, AdminAccessService access, AuditService audit) {
        this.notifications = notifications;
        this.access = access;
        this.audit = audit;
    }

    @PostMapping
    @Operation(summary = "Send a push notification, with an optional picture, to every registered phone")
    public AnnouncementDtos.SendResponse send(@Valid @RequestBody AnnouncementDtos.SendRequest request) {
        AdminUser actor = access.requireCanEditSettings();
        String imageUrl = request.imageUrl() == null || request.imageUrl().isBlank()
                ? null
                : request.imageUrl().trim();

        int devices = notifications.broadcast(request.title().trim(), request.body().trim(), imageUrl);

        audit.record(
                new AuditService.Actor(actor.getUserId(), actor.getName(), actor.getEmail()),
                AuditCategory.GENERAL,
                "Sent an announcement",
                "\"" + request.title().trim() + "\" to " + devices + " phone(s)"
                        + (imageUrl == null ? "" : ", with the picture " + imageUrl));

        return new AnnouncementDtos.SendResponse(devices);
    }
}
