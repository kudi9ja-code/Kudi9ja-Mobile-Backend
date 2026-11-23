package com.quadrilateral.kudi9ja.domain.loan;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** The one row recording the first day late charges were counted: the day they went live. */
@Entity
@Table(name = "late_charge_policy")
@Getter
@Setter
@NoArgsConstructor
public class LateChargePolicy {

    public static final int ID = 1;

    @Id
    @Column(name = "id", nullable = false)
    private Integer id;

    @Column(name = "starts_on")
    private LocalDate startsOn;
}
