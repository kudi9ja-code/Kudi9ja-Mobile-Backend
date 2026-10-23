package com.quadrilateral.kudi9ja.support;

import com.quadrilateral.kudi9ja.integration.paystack.PaystackGateway;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Paystack, as far as a test needs it.
 *
 * <p>Checkouts open; what {@link #verify} reports for a reference is whatever
 * the test said the customer did. A webhook is "signed" when its signature is
 * {@link #SIGNATURE}. Transfers answer with {@link #transferStatus}, and every
 * one is kept so a test can see what was sent.
 */
public class FakePaystackGateway implements PaystackGateway {

    public static final String SIGNATURE = "signed-by-the-fake";

    private final Map<String, Charge> charges = new ConcurrentHashMap<>();
    private final Map<String, Long> asked = new ConcurrentHashMap<>();
    private final List<String> transfers = new ArrayList<>();
    private final AtomicInteger verifications = new AtomicInteger();
    public volatile String transferStatus = "pending";

    @Override
    public Initialized initialize(String email, long amountKobo, String reference, String callbackUrl,
            Map<String, Object> metadata) {
        asked.put(reference, amountKobo);
        return new Initialized("https://checkout.paystack.test/" + reference, "access-" + reference, reference);
    }

    /** The customer paid what they were asked. */
    public void paid(String reference) {
        paid(reference, asked.getOrDefault(reference, 0L));
    }

    /** The customer paid this much, which may not be what they were asked. */
    public void paid(String reference, long kobo) {
        charges.put(reference, new Charge(reference, "success", kobo, "NGN", Instant.now(), "card",
                "Approved", 1500L, "visa", "4081", "TEST BANK"));
    }

    public void failed(String reference) {
        charges.put(reference, new Charge(reference, "failed", asked.getOrDefault(reference, 0L), "NGN", null,
                "card", "Declined", null, null, null, null));
    }

    @Override
    public Charge verify(String reference) {
        verifications.incrementAndGet();
        return charges.getOrDefault(reference, new Charge(reference, "ongoing", asked.getOrDefault(reference, 0L),
                "NGN", null, null, null, null, null, null, null));
    }

    @Override
    public String createRecipient(String name, String accountNumber, String bankCode) {
        return "RCP_" + accountNumber;
    }

    @Override
    public synchronized Transfer transfer(long amountKobo, String recipientCode, String reference, String reason) {
        transfers.add(reference);
        return new Transfer(transferStatus, "TRF_" + transfers.size(), reference);
    }

    /** Account numbers the fake bank knows, and whose they are. */
    public final Map<String, String> accounts = new ConcurrentHashMap<>();

    @Override
    public AccountLookup resolveAccount(String accountNumber, String bankCode) {
        String name = accounts.get(accountNumber);
        return name == null
                ? new AccountLookup(false, null, "No account was found with that number at that bank. Check both and try again.")
                : new AccountLookup(true, name, null);
    }

    @Override
    public boolean signatureValid(byte[] body, String signature) {
        return SIGNATURE.equals(signature);
    }

    public synchronized List<String> transfers() {
        return List.copyOf(transfers);
    }

    public int verifications() {
        return verifications.get();
    }

    @TestConfiguration
    public static class Config {

        @Bean
        public FakePaystackGateway fakePaystackGateway() {
            return new FakePaystackGateway();
        }
    }
}
