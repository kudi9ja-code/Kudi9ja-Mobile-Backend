package com.quadrilateral.kudi9ja.domain.credit;

import com.quadrilateral.kudi9ja.common.error.ApiException;
import com.quadrilateral.kudi9ja.common.error.ErrorCode;
import com.quadrilateral.kudi9ja.domain.admin.AdminAccessService;
import com.quadrilateral.kudi9ja.domain.admin.AdminUser;
import com.quadrilateral.kudi9ja.domain.audit.AuditCategory;
import com.quadrilateral.kudi9ja.domain.audit.AuditService;
import com.quadrilateral.kudi9ja.domain.user.User;
import com.quadrilateral.kudi9ja.domain.user.UserRepository;
import com.quadrilateral.kudi9ja.integration.credit.CreditBureau;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A customer's credit score from the bureaus, looked up by their BVN.
 *
 * <p>Admin panel only; the customer never sees it. Looking one up costs money,
 * so it is fetched only when an admin who can act on loans presses "Check",
 * and every check is kept and written to the audit log. Any admin may read
 * the checks already made.
 */
@Service
public class CreditCheckService {

    private final CreditCheckRepository checks;
    private final UserRepository users;
    private final AdminAccessService access;
    private final AuditService audit;
    private final ObjectProvider<CreditBureau> bureau;

    public CreditCheckService(
            CreditCheckRepository checks,
            UserRepository users,
            AdminAccessService access,
            AuditService audit,
            ObjectProvider<CreditBureau> bureau) {
        this.checks = checks;
        this.users = users;
        this.access = access;
        this.audit = audit;
        this.bureau = bureau;
    }

    /** Checks already made, newest first. */
    @Transactional(readOnly = true)
    public List<CreditCheck> history(UUID customerId) {
        access.requireCanView();
        require(customerId);
        return checks.findTop20ByUserIdOrderByCheckedAtDesc(customerId);
    }

    /** Whether a check can be run at all on this server. */
    public boolean available() {
        return bureau.getIfAvailable() != null;
    }

    /** Fetches a fresh score from the bureaus. Charged to the Dojah wallet. */
    @Transactional
    public CreditCheck check(UUID customerId) {
        AdminUser actor = access.requireCanActOnLoans();
        User user = require(customerId);

        CreditBureau provider = bureau.getIfAvailable();
        if (provider == null) {
            throw new ApiException(ErrorCode.FEATURE_DISABLED,
                    "Credit checks are not set up. Add the Dojah keys on the server.");
        }
        String bvn = user.getBvn() == null ? "" : user.getBvn().trim();
        if (bvn.length() != 11 || !bvn.chars().allMatch(Character::isDigit)) {
            throw ApiException.validation("This customer has no BVN on file, so their credit cannot be checked.");
        }

        CreditCheck saved = checks.save(CreditCheck.of(user.getId(), actor.getName(), provider.lookup(bvn)));

        audit.record(
                new AuditService.Actor(actor.getUserId(), actor.getName(), actor.getEmail()),
                AuditCategory.DATA_ACCESS,
                "Credit score checked",
                actor.getName() + " checked the credit score of " + user.getFullName()
                        + " (" + user.getCustomerRef() + ") with the credit bureaus.",
                user.getId(),
                user.getCustomerRef());
        return saved;
    }

    private User require(UUID customerId) {
        return users.findById(customerId).orElseThrow(() -> ApiException.notFound("That customer"));
    }
}
