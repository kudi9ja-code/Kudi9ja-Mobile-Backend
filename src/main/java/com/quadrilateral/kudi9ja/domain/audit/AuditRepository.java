package com.quadrilateral.kudi9ja.domain.audit;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Reads and one append. Deliberately not a {@code JpaRepository}: the audit log
 * is append-only, so {@code delete} and {@code saveAll}-style bulk edits are
 * not offered at all rather than merely discouraged.
 */
public interface AuditRepository extends Repository<AuditEntry, UUID> {

    AuditEntry save(AuditEntry entry);

    /**
     * The panel's search over the log. Every filter is optional.
     *
     * <p>Every parameter that can arrive null is cast. The obvious spelling —
     * {@code :from is null or a.occurredAt >= :from} — puts the same value in
     * two places, and Postgres sees two parameters: the second takes its type
     * from the column it is compared against, the first is asked to have a type
     * worked out from nothing at all. It will not, and it refuses the whole
     * statement with "could not determine data type of parameter $5" — which is
     * the bare {@code :from}, and which took the audit log out entirely.
     *
     * <p>{@code :category} and {@code :subjectId} escape it because the driver
     * already sends an OID for an enum and a UUID, but the two instants and the
     * free-text term have to say their type out loud. H2 infers all of them
     * happily, which is why this has to be remembered rather than discovered in
     * a test.
     */
    @Query("""
            select a from AuditEntry a
             where (:category is null or a.category = :category)
               and (:subjectId is null or a.subjectId = :subjectId)
               and (cast(:from as Instant) is null or a.occurredAt >= :from)
               and (cast(:to as Instant) is null or a.occurredAt < :to)
               and (coalesce(cast(:q as String), '') = ''
                    or lower(a.action) like lower(concat('%', :q, '%'))
                    or lower(a.detail) like lower(concat('%', :q, '%'))
                    or lower(a.actor) like lower(concat('%', :q, '%')))
             order by a.occurredAt desc
            """)
    Page<AuditEntry> search(
            @Param("category") AuditCategory category,
            @Param("subjectId") UUID subjectId,
            @Param("from") Instant from,
            @Param("to") Instant to,
            @Param("q") String query,
            Pageable pageable);

    long count();
}
