package com.quadrilateral.kudi9ja.web.dto;

import java.time.Instant;
import java.util.List;

/** A report for the panel to turn into Excel or PDF: columns, then rows of cells in the same order. */
public final class ExportDtos {

    private ExportDtos() {
    }

    /** How a column is shown: money in naira, a date and time, a plain number, or text. */
    public enum ColumnType {
        TEXT,
        MONEY,
        NUMBER,
        DATE
    }

    public record Column(String label, ColumnType type) {
    }

    /**
     * @param period      "All time", "2026-10-01 to 2026-10-31" and so on
     * @param generatedBy who downloaded it, for the footer of the file
     */
    public record Report(
            String title,
            String period,
            Instant generatedAt,
            String generatedBy,
            String company,
            List<Column> columns,
            List<List<Object>> rows) {

        public static Report of(List<Column> columns, List<List<Object>> rows) {
            return new Report(null, null, null, null, null, columns, rows);
        }

        public Report stamped(String title, String period, String generatedBy, String company) {
            return new Report(title, period, Instant.now(), generatedBy, company, columns, rows);
        }
    }
}
