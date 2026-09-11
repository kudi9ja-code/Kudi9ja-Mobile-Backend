package com.quadrilateral.kudi9ja.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Borrowing, end to end, for tests that need a disbursed loan.
 *
 * <p>Getting one used to be a single POST. It is now an application with four
 * files and two guarantors on it, followed by an admin approving — which is a
 * great deal to retype in every test that merely wants a loan on the books in
 * order to check something else. So it lives here, and the tests that care
 * about the flow itself drive the endpoints directly instead.
 *
 * <p>The uploaded bytes are not real documents and do not need to be. Nothing
 * in the server decodes them: a statement is stored, signed for, and looked at
 * by a person.
 */
public final class BorrowFlow {

    private final MockMvc mvc;
    private final ObjectMapper json;

    public BorrowFlow(MockMvc mvc, ObjectMapper json) {
        this.mvc = mvc;
        this.json = json;
    }

    /** A well-formed guarantor. The BVN is only ever checked for shape. */
    public static Map<String, Object> guarantor(String name, String phone, String bvn) {
        return Map.of(
                "fullName", name,
                "phone", phone,
                "address", "22 Awolowo Road, Ikoyi, Lagos",
                "relationship", "Business partner",
                "bvn", bvn,
                "occupation", "Trader",
                "email", "");
    }

    /** The two guarantors every application needs. */
    public static List<Map<String, Object>> twoGuarantors() {
        return List.of(
                guarantor("Adaeze Nwosu", "08031234567", "22222222222"),
                guarantor("Tunde Bakare", "08061234567", "33333333333"));
    }

    /** Submits an application and returns the created row. */
    public JsonNode apply(
            SignUpFlow.Session customer,
            String amount,
            int months,
            String purpose) throws Exception {

        Map<String, Object> form = Map.of(
                "amount", new BigDecimal(amount),
                "months", months,
                "purpose", purpose,
                "businessName", "Chioma Grace Provisions",
                "businessAddress", "14 Adeola Odeku Street, Victoria Island, Lagos",
                "monthlyIncome", new BigDecimal("450000"),
                "guarantors", twoGuarantors(),
                "pin", SignUpFlow.PIN);

        MvcResult result = mvc.perform(multipart("/api/v1/loans/applications")
                        .file(new MockMultipartFile("form", "form.json",
                                MediaType.APPLICATION_JSON_VALUE, json.writeValueAsBytes(form)))
                        .file(file("bankStatement", "statement.pdf", "application/pdf"))
                        .file(file("businessPhotos", "front.jpg", "image/jpeg"))
                        .file(file("businessPhotos", "inside.jpg", "image/jpeg"))
                        .file(file("businessPhotos", "stock.jpg", "image/jpeg"))
                        .header("Authorization", customer.bearer()))
                .andExpect(status().isCreated())
                .andReturn();

        return read(result);
    }

    /** Applies, then has the admin approve — a disbursed loan, as before. */
    public JsonNode borrow(
            SignUpFlow.Session customer,
            SignUpFlow.Session admin,
            String amount,
            int months,
            String purpose) throws Exception {

        JsonNode application = apply(customer, amount, months, purpose);
        return approve(admin, application.get("id").asText());
    }

    public JsonNode approve(SignUpFlow.Session admin, String applicationId) throws Exception {
        return read(mvc.perform(
                        post("/api/v1/admin/loan-applications/{id}/approve", applicationId)
                                .header("Authorization", admin.bearer())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isOk())
                .andReturn());
    }

    public JsonNode reject(
            SignUpFlow.Session admin, String applicationId, String reason) throws Exception {

        return read(mvc.perform(
                        post("/api/v1/admin/loan-applications/{id}/reject", applicationId)
                                .header("Authorization", admin.bearer())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(Map.of("reason", reason))))
                .andExpect(status().isOk())
                .andReturn());
    }

    private static MockMultipartFile file(String part, String name, String type) {
        return new MockMultipartFile(part, name, type, (name + " bytes").getBytes());
    }

    private JsonNode read(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }
}
