package com.quadrilateral.kudi9ja.domain.loanapplication;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Somebody who vouches for a borrower.
 *
 * <p>Two are required on every application. They are not customers: no account
 * is opened for them, nothing is checked against an institution, and their BVN
 * is <b>recorded, not verified</b>. Verifying a BVN means asking the issuer
 * about a person, and we have that person's consent for their own number only —
 * the borrower's word that a guarantor agreed is not consent from the
 * guarantor. What the number is for is identification if this loan ever has to
 * be pursued, and an admin reading the application can see whether it is even
 * well-formed.
 *
 * <p>Stored on the application rather than as an entity of their own. A
 * guarantor is a fact about <i>this</i> application: the same person vouching
 * twice is two statements, made on two days, either of which they may since
 * have thought better of.
 */
@Embeddable
@Getter
@Setter
@NoArgsConstructor
public class Guarantor {

    @Column(name = "full_name", nullable = false, length = 160)
    private String fullName;

    @Column(name = "phone", nullable = false, length = 20)
    private String phone;

    @Column(name = "address", nullable = false, length = 400)
    private String address;

    /** What they are to the borrower: an employer, a sister, a landlord. */
    @Column(name = "relationship", nullable = false, length = 120)
    private String relationship;

    /** Eleven digits. Recorded as given; see the note on this class. */
    @Column(name = "bvn", nullable = false, length = 11)
    private String bvn;

    /** Worth having: a guarantor with no income is not much of a guarantee. */
    @Column(name = "occupation", length = 160)
    private String occupation;

    @Column(name = "email", length = 200)
    private String email;
}
