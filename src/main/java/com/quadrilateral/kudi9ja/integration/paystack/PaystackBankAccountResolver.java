package com.quadrilateral.kudi9ja.integration.paystack;

import com.quadrilateral.kudi9ja.common.error.ApiException;
import com.quadrilateral.kudi9ja.common.error.ErrorCode;
import com.quadrilateral.kudi9ja.integration.bank.BankAccountResolver;
import com.quadrilateral.kudi9ja.integration.bank.BankDirectory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Who owns a payout account, asked of the bank through Paystack.
 *
 * <p>Present whenever Paystack is configured, and preferred over the
 * self-declared resolver: with it, a withdrawal account must really be in the
 * customer's own name, and a mistyped number is caught before money is sent.
 * The lookup is free and runs only when an account is added or changed.
 *
 * <p>If Paystack cannot be reached, saving the account fails with a message to
 * try again — an unchecked account is never saved as if it had been checked.
 */
@Component
@Primary
@ConditionalOnExpression("!'${kudi9ja.paystack.secret-key:}'.isBlank()")
public class PaystackBankAccountResolver implements BankAccountResolver {

    private final PaystackGateway paystack;

    public PaystackBankAccountResolver(PaystackGateway paystack) {
        this.paystack = paystack;
    }

    @Override
    public Resolution resolve(String bankCode, String accountNumber) {
        if (accountNumber == null || accountNumber.length() != 10
                || !accountNumber.chars().allMatch(Character::isDigit)) {
            return Resolution.failed("An account number is ten digits.");
        }
        if (BankDirectory.byCode(bankCode).isEmpty()) {
            return Resolution.failed("We do not recognise that bank.");
        }
        PaystackGateway.AccountLookup lookup;
        try {
            lookup = paystack.resolveAccount(accountNumber, bankCode);
        } catch (PaystackGateway.PaystackException e) {
            throw new ApiException(ErrorCode.INTERNAL, e.getMessage());
        }
        return lookup.found()
                ? new Resolution(true, lookup.accountName(), bankCode, null)
                : Resolution.failed(lookup.reason());
    }
}
