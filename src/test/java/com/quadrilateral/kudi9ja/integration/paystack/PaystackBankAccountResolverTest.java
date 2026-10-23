package com.quadrilateral.kudi9ja.integration.paystack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.quadrilateral.kudi9ja.common.error.ApiException;
import com.quadrilateral.kudi9ja.domain.kyc.KycService;
import com.quadrilateral.kudi9ja.integration.kyc.SelfDeclaredIdentityVerifier;
import com.quadrilateral.kudi9ja.support.FakePaystackGateway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Payout accounts are checked with the bank through Paystack: the account
 * must exist, and be in the customer's own name.
 */
@DisplayName("Payout account check through Paystack")
class PaystackBankAccountResolverTest {

    private final FakePaystackGateway paystack = new FakePaystackGateway();
    private final KycService kyc = new KycService(new SelfDeclaredIdentityVerifier(), new PaystackBankAccountResolver(paystack));

    @Test
    @DisplayName("an account in the customer's own name is accepted, with the bank's name kept")
    void ownAccount() {
        paystack.accounts.put("0123456789", "ADEYEMI CHIOMA GRACE");
        assertThat(kyc.verifyPayoutAccount("Zenith Bank", "0123456789", "Chioma Grace Adeyemi"))
                .isEqualTo("ADEYEMI CHIOMA GRACE");
    }

    @Test
    @DisplayName("an account in somebody else's name is refused")
    void somebodyElses() {
        paystack.accounts.put("0123456789", "TUNDE BAKARE");
        assertThatThrownBy(() -> kyc.verifyPayoutAccount("Zenith Bank", "0123456789", "Chioma Grace Adeyemi"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("not in your name");
    }

    @Test
    @DisplayName("a number the bank does not know is refused before anything is saved")
    void unknownAccount() {
        assertThatThrownBy(() -> kyc.verifyPayoutAccount("Zenith Bank", "0999999999", "Chioma Grace Adeyemi"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("No account was found");
    }
}
