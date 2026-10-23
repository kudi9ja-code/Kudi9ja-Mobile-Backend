package com.quadrilateral.kudi9ja.domain.loan;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A guarantor on a loan made outside the app.
 *
 * <p>Looser than the in-app {@code Guarantor}, which demands every field
 * because the applicant is filling in a form. Here an admin is copying a paper
 * record, and a notebook that never noted a landmark should not stop the loan
 * being entered. Only the name is required.
 */
@Embeddable
@Getter
@Setter
@NoArgsConstructor
public class OfflineGuarantor {

    @Column(name = "full_name", nullable = false, length = 160)
    private String fullName;

    @Column(name = "phone", length = 20)
    private String phone;

    @Column(name = "address", length = 400)
    private String address;

    @Column(name = "state", length = 60)
    private String state;

    @Column(name = "local_government", length = 120)
    private String localGovernment;

    @Column(name = "landmark", length = 200)
    private String landmark;

    @Column(name = "relationship", length = 120)
    private String relationship;

    @Column(name = "bvn", length = 11)
    private String bvn;

    @Column(name = "occupation", length = 160)
    private String occupation;

    @Column(name = "email", length = 200)
    private String email;
}
