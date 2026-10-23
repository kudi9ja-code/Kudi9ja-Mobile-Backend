package com.quadrilateral.kudi9ja.domain.settings;

import com.quadrilateral.kudi9ja.common.error.ApiException;
import com.quadrilateral.kudi9ja.common.error.ErrorCode;
import com.quadrilateral.kudi9ja.domain.audit.AuditCategory;
import com.quadrilateral.kudi9ja.domain.audit.AuditService;
import java.util.LinkedHashMap;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads and replaces the settings document.
 *
 * <p>Settings are never edited in place. A save validates the whole document,
 * diffs it against the version it replaces, writes a <b>new</b> version, and
 * records the diff in the audit log. The change takes effect immediately for
 * everything priced from now on, and touches nothing already running: every
 * loan carries the rate it was priced at and every savings plan carries its own
 * interest.
 */
@Service
public class SettingsService {

    private static final Logger log = LoggerFactory.getLogger(SettingsService.class);

    private final PlatformSettingsRepository repository;
    private final AuditService audit;

    public SettingsService(PlatformSettingsRepository repository, AuditService audit) {
        this.repository = repository;
        this.audit = audit;
    }

    /**
     * The version in force. Seeds version one on an empty database, so a fresh
     * deployment prices exactly as the client's compiled-in defaults do.
     */
    @Transactional
    public PlatformSettings current() {
        return repository.findFirstByOrderByVersionDesc().orElseGet(this::seed);
    }

    @Transactional(readOnly = true)
    public PlatformSettings currentReadOnly() {
        return repository.findFirstByOrderByVersionDesc()
                .orElseThrow(() -> new ApiException(
                        ErrorCode.INTERNAL, "Platform settings have not been initialised."));
    }

    @Transactional(readOnly = true)
    public Page<PlatformSettings> history(Pageable pageable) {
        return repository.findAllByOrderByVersionDesc(pageable);
    }

    @Transactional(readOnly = true)
    public PlatformSettings version(long version) {
        return repository.findById(version)
                .orElseThrow(() -> ApiException.notFound("That settings version"));
    }

    /**
     * Saves a change as a new version.
     *
     * @param draft       the whole document as it should now read
     * @param expectedVersion the version the editor was working from, so two
     *                        admins editing at once cannot silently overwrite
     *                        each other; null skips the check
     * @param actor       who made the change, for the audit entry
     * @return the version now in force
     */
    @Transactional
    public PlatformSettings save(PlatformSettings draft, Long expectedVersion, AuditService.Actor actor) {
        PlatformSettings before = current();

        if (expectedVersion != null && !expectedVersion.equals(before.getVersion())) {
            throw new ApiException(
                    ErrorCode.SETTINGS_STALE,
                    "Someone else changed the settings while you were editing. Reload and try again.",
                    java.util.Map.of("currentVersion", before.getVersion(), "yourVersion", expectedVersion));
        }

        SettingsValidator.validate(draft);

        List<String> changes = SettingsDiff.between(before, draft);
        if (changes.isEmpty()) {
            // Nothing moved. Writing a version for a no-op would fill the
            // history with rows that tell a reader nothing.
            return before;
        }

        draft.setVersion(before.getVersion() + 1);
        draft.setCreatedAt(java.time.Instant.now());
        draft.setCreatedBy(actor == null ? "System" : actor.describe());
        // The map came off a detached parent; copy it so JPA writes a fresh
        // collection against the new version rather than moving the old rows.
        draft.setLoanRates(new LinkedHashMap<>(draft.getLoanRates()));
        draft.setGroupLoanRates(new LinkedHashMap<>(draft.getGroupLoanRates()));

        PlatformSettings saved = repository.save(draft);

        audit.record(
                actor,
                AuditCategory.SETTINGS,
                "Settings updated",
                "Version " + before.getVersion() + " → " + saved.getVersion() + ". "
                        + String.join(" • ", changes));

        log.info("Platform settings moved to version {} by {}", saved.getVersion(), saved.getCreatedBy());
        return saved;
    }

    /** Refuses the request outright while maintenance mode is on. */
    public void requireNotInMaintenance() {
        if (currentReadOnly().isMaintenanceMode()) {
            throw new ApiException(
                    ErrorCode.MAINTENANCE,
                    "Kudi9ja is briefly down for maintenance. Please try again shortly.");
        }
    }

    public void requireSavingsEnabled() {
        if (!currentReadOnly().isSavingsEnabled()) {
            throw new ApiException(ErrorCode.FEATURE_DISABLED, "Savings is not accepting new plans right now.");
        }
    }

    public void requireLendingEnabled() {
        if (!currentReadOnly().isLendingEnabled()) {
            throw new ApiException(ErrorCode.FEATURE_DISABLED, "Lending is not open right now.");
        }
    }

    public void requireThriftEnabled() {
        if (!currentReadOnly().isThriftEnabled()) {
            throw new ApiException(ErrorCode.FEATURE_DISABLED, "Thrift circles are not open right now.");
        }
    }

    private PlatformSettings seed() {
        PlatformSettings first = repository.save(SettingsDefaults.first());
        audit.recordSystem(
                AuditCategory.SETTINGS,
                "Settings seeded",
                "Version 1 written from the shipped defaults: "
                        + "17% savings, 12.5%–134% lending across 1–24 months, "
                        + "₦5,000 flat management fee to ₦250,000 then 2%.");
        log.info("Seeded platform settings version 1");
        return first;
    }
}
