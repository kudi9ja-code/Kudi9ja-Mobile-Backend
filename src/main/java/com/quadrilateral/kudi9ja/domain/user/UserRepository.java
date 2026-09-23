package com.quadrilateral.kudi9ja.domain.user;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmailIgnoreCase(String email);

    Optional<User> findByCustomerRef(String customerRef);

    Optional<User> findByPhone(String phone);

    boolean existsByEmailIgnoreCase(String email);

    boolean existsByPhone(String phone);

    boolean existsByBvn(String bvn);

    Optional<User> findByBvn(String bvn);

    boolean existsByNin(String nin);

    /**
     * The admin customer list. Matches on the things an admin actually has in
     * front of them: a name, an email, a phone number, or the customer
     * reference off a bank narration.
     *
     * <p>The term is cast for Postgres; see {@link
     * com.quadrilateral.kudi9ja.domain.audit.AuditRepository}.
     */
    @Query("""
            select u from User u
             where (coalesce(cast(:q as String), '') = ''
                    or lower(u.fullName) like lower(concat('%', :q, '%'))
                    or lower(u.email) like lower(concat('%', :q, '%'))
                    or u.phone like concat('%', :q, '%')
                    or upper(u.customerRef) like upper(concat('%', :q, '%')))
               and (:status is null or u.accountStatus = :status)
            """)
    Page<User> search(
            @Param("q") String query,
            @Param("status") AccountStatus status,
            Pageable pageable);

    /**
     * Accounts that have gone quiet. The dormancy sweep freezes these after
     * notifying the customer, and they are released on re-verification.
     */
    @Query("""
            select u from User u
             where u.accountStatus = com.quadrilateral.kudi9ja.domain.user.AccountStatus.ACTIVE
               and coalesce(u.lastActiveAt, u.createdAt) < :before
            """)
    List<User> findDormantCandidates(@Param("before") Instant before);

    long countByAccountStatus(AccountStatus status);

    long countByCreatedAtAfter(Instant since);

    /**
     * Closed accounts whose retention period has finally run out.
     *
     * <p>A deletion request cannot override the AML rules: identity and
     * transaction records are kept for at least five years after the
     * relationship ends, so closing an account redacts the credentials and
     * leaves the record. This is the other half of that promise — without it,
     * "retained for five years" quietly means "kept forever", and a customer
     * who asked to be forgotten never is.
     */
    @Query("""
            select u from User u
             where u.accountStatus = com.quadrilateral.kudi9ja.domain.user.AccountStatus.CLOSED
               and u.retainUntil is not null
               and u.retainUntil <= :now
            """)
    List<User> findDueForErasure(@Param("now") Instant now);
}
