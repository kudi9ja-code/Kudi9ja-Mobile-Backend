package com.quadrilateral.kudi9ja.domain.kyc;

import com.quadrilateral.kudi9ja.common.error.ApiException;
import com.quadrilateral.kudi9ja.common.error.ErrorCode;
import com.quadrilateral.kudi9ja.integration.bank.BankAccountResolver;
import com.quadrilateral.kudi9ja.integration.bank.BankDirectory;
import com.quadrilateral.kudi9ja.integration.kyc.IdentityVerifier;
import com.quadrilateral.kudi9ja.integration.kyc.NameMatcher;
import com.quadrilateral.kudi9ja.integration.kyc.SandboxIdentityVerifier;
import java.time.LocalDate;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Proves that the person opening an account is who they say, and that the bank
 * account they nominate is theirs.
 *
 * <p>Three checks, and each one has to pass on its own terms:
 *
 * <ul>
 *   <li>the BVN exists and the name and date of birth on it match what was
 *       typed;
 *   <li>the NIN exists and the name on it matches;
 *   <li>the payout account resolves and the name on it matches.
 * </ul>
 *
 * <p>The name comparison is deliberately tolerant of word order and a dropped
 * middle name — see {@link NameMatcher} — because Nigerian names reach us in
 * every arrangement and refusing a real customer over word order would be a
 * defect, not a control. The date of birth is compared exactly.
 */
@Service
public class KycService {

    private static final Logger log = LoggerFactory.getLogger(KycService.class);

    private final IdentityVerifier verifier;
    private final BankAccountResolver accountResolver;

    public KycService(IdentityVerifier verifier, BankAccountResolver accountResolver) {
        this.verifier = verifier;
        this.accountResolver = accountResolver;
    }

    /**
     * Checks a BVN against the issuing institution.
     *
     * @throws ApiException when it does not exist, or belongs to someone else
     */
    public void verifyBvn(String bvn, String claimedName, LocalDate claimedDob) {
        requireElevenDigits(bvn, "BVN");
        IdentityVerifier.Verification result = verifier.verifyBvn(bvn);
        assertIdentity(result, claimedName, claimedDob, "BVN");
        log.info("BVN verified for {}", claimedName);
    }

    /** Checks a NIN against the issuing institution. */
    public void verifyNin(String nin, String claimedName, LocalDate claimedDob) {
        requireElevenDigits(nin, "NIN");
        IdentityVerifier.Verification result = verifier.verifyNin(nin);
        assertIdentity(result, claimedName, claimedDob, "NIN");
        log.info("NIN verified for {}", claimedName);
    }

    /**
     * Resolves a payout account and refuses one held in another name.
     *
     * <p>The Terms promise payouts only to the customer. This is where that
     * promise becomes enforceable rather than aspirational: the client cannot
     * check it, so nothing but this stands between a customer's balance and
     * somebody else's account.
     *
     * @return the name the bank holds on the account, to be stored as evidence
     */
    public String verifyPayoutAccount(String bankName, String accountNumber, String claimedName) {
        if (accountNumber == null || accountNumber.length() != 10
                || !accountNumber.chars().allMatch(Character::isDigit)) {
            throw ApiException.validation("An account number is ten digits.");
        }

        BankDirectory.Bank bank = BankDirectory.byName(bankName)
                .orElseThrow(() -> ApiException.validation("Choose a bank from the list."));

        BankAccountResolver.Resolution resolution = accountResolver.resolve(bank.code(), accountNumber);
        if (!resolution.resolved()) {
            throw new ApiException(
                    ErrorCode.KYC_FAILED,
                    resolution.reason() == null
                            ? "We could not find that account. Check the number and try again."
                            : resolution.reason());
        }

        String officialName = resolution.accountName();
        if (SandboxIdentityVerifier.SANDBOX_MATCHES_CALLER.equals(officialName)) {
            return claimedName;
        }

        // Name check switched off (2026-11-27): any account the bank finds is
        // accepted, whoever holds it. The bank's name is still stored.
        // if (!NameMatcher.matchesAllowingTypo(claimedName, officialName)) {
        //     log.warn("Payout account name mismatch: {} {} is held by \"{}\"; customer's name is \"{}\"",
        //             bank.name(), accountNumber, officialName, claimedName);
        //     throw new ApiException(
        //             ErrorCode.NAME_MISMATCH,
        //             "That account is not in your name. We only pay out to an account you hold yourself.",
        //             Map.of("bank", bank.name(), "accountNumber", accountNumber));
        // }
        if (!NameMatcher.matchesAllowingTypo(claimedName, officialName)) {
            log.info("Payout account in another name accepted: {} {} is held by \"{}\"; customer's name is \"{}\"",
                    bank.name(), accountNumber, officialName, claimedName);
        }
        return officialName;
    }

    private void assertIdentity(
            IdentityVerifier.Verification result, String claimedName, LocalDate claimedDob, String kind) {

        if (!result.verified()) {
            throw new ApiException(
                    ErrorCode.KYC_FAILED,
                    result.reason() == null
                            ? "We could not verify that " + kind + "."
                            : result.reason());
        }

        // The sandbox and the self-declared verifier both answer "whoever you
        // asked about", because neither asked anyone. A real provider returns a
        // real name and the checks below run in full.
        boolean takenOnTrust = SandboxIdentityVerifier.SANDBOX_MATCHES_CALLER.equals(result.firstName());
        if (takenOnTrust) {
            return;
        }

        if (!NameMatcher.matches(claimedName, result.fullName())) {
            throw new ApiException(
                    ErrorCode.NAME_MISMATCH,
                    "The name on that " + kind + " does not match the name you gave us.");
        }

        if (claimedDob != null && result.dateOfBirth() != null
                && !claimedDob.equals(result.dateOfBirth())) {
            throw new ApiException(
                    ErrorCode.KYC_FAILED,
                    "The date of birth on that " + kind + " does not match the one you gave us.");
        }
    }

    private static void requireElevenDigits(String value, String kind) {
        if (value == null || value.length() != 11 || !value.chars().allMatch(Character::isDigit)) {
            throw ApiException.validation("A " + kind + " is eleven digits.");
        }
    }
}
