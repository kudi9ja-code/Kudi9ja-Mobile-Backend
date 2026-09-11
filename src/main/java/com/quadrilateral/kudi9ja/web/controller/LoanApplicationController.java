package com.quadrilateral.kudi9ja.web.controller;

import com.quadrilateral.kudi9ja.common.error.ApiException;
import com.quadrilateral.kudi9ja.domain.loanapplication.Guarantor;
import com.quadrilateral.kudi9ja.domain.loanapplication.LoanApplicationService;
import com.quadrilateral.kudi9ja.security.auth.CurrentUser;
import com.quadrilateral.kudi9ja.web.dto.LoanApplicationDtos;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Applying to borrow.
 *
 * <p>This replaced an endpoint that lent money in the time it took to answer a
 * request. The arithmetic it did was sound and still runs — but it was the only
 * thing standing between a stranger and a disbursement, and a lender whose
 * whole underwriting is a formula over its own records has no way of knowing
 * whether the business it is lending against exists.
 *
 * <p>So an application is submitted with evidence — a bank statement, three
 * photographs of the premises, two guarantors in full — and <b>nothing is
 * credited until an admin has read it</b> and approved. A refusal comes with a
 * reason written for the customer, because the point of refusing is that they
 * can fix what was wrong and come back.
 *
 * <p>The form and the files travel together as one multipart request. The
 * fields arrive as a JSON part rather than as loose form values: two guarantors
 * are a repeated structure, and flattening them into numbered field names makes
 * the shape a convention both sides have to remember.
 */
@RestController
@RequestMapping("/api/v1/loans/applications")
@Tag(name = "Borrowing", description = "Applying to borrow, and what became of it")
public class LoanApplicationController {

    private final LoanApplicationService applications;
    private final CurrentUser currentUser;

    public LoanApplicationController(
            LoanApplicationService applications, CurrentUser currentUser) {
        this.applications = applications;
        this.currentUser = currentUser;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Apply to borrow, with a bank statement, business photos and two guarantors")
    public ResponseEntity<LoanApplicationDtos.LoanApplicationResponse> apply(
            @RequestPart("form") @Valid LoanApplicationDtos.ApplicationFormRequest form,
            @RequestPart("bankStatement") MultipartFile bankStatement,
            @RequestPart("businessPhotos") List<MultipartFile> businessPhotos) {

        UUID userId = currentUser.requireId();

        List<Guarantor> guarantors = new ArrayList<>();
        if (form.guarantors() != null) {
            form.guarantors().forEach(g -> guarantors.add(g.toGuarantor()));
        }

        List<LoanApplicationService.Upload> photos = new ArrayList<>();
        for (MultipartFile photo : businessPhotos) {
            photos.add(read(photo, "photograph"));
        }

        LoanApplicationDtos.LoanApplicationResponse response =
                LoanApplicationDtos.LoanApplicationResponse.from(applications.submit(
                        userId,
                        new LoanApplicationService.Submission(
                                form.amount(),
                                form.months(),
                                form.purpose(),
                                form.businessName(),
                                form.businessAddress(),
                                form.monthlyIncome(),
                                guarantors,
                                read(bankStatement, "bank statement"),
                                photos,
                                form.pin())));

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    @Operation(summary = "Every application this customer has made")
    public List<LoanApplicationDtos.LoanApplicationResponse> mine() {
        return applications.mine(currentUser.requireId()).stream()
                .map(LoanApplicationDtos.LoanApplicationResponse::from)
                .toList();
    }

    @GetMapping("/{applicationId}")
    @Operation(summary = "One application, including the reason if it was declined")
    public LoanApplicationDtos.LoanApplicationResponse one(@PathVariable UUID applicationId) {
        return LoanApplicationDtos.LoanApplicationResponse.from(
                applications.mine(currentUser.requireId(), applicationId));
    }

    @PostMapping("/{applicationId}/withdraw")
    @Operation(summary = "Withdraw an application nobody has decided yet")
    public LoanApplicationDtos.LoanApplicationResponse withdraw(@PathVariable UUID applicationId) {
        return LoanApplicationDtos.LoanApplicationResponse.from(
                applications.cancel(currentUser.requireId(), applicationId));
    }

    /**
     * Reads one uploaded part.
     *
     * <p>Size and type are checked in the service, against the same limits a
     * receipt is held to. What is checked here is only that something arrived:
     * an empty part is a client bug, and saying so plainly beats a validation
     * message about file types for a file that is not there.
     */
    private static LoanApplicationService.Upload read(MultipartFile file, String what) {
        if (file == null || file.isEmpty()) {
            throw ApiException.validation("Attach your " + what + ".");
        }
        try {
            return new LoanApplicationService.Upload(
                    file.getOriginalFilename(), file.getContentType(), file.getBytes());
        } catch (IOException e) {
            throw ApiException.validation(
                    "That " + what + " could not be read. Try attaching it again.");
        }
    }
}
