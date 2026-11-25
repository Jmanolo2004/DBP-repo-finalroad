package com.fidegresa.report;

import com.fidegresa.common.DateRange;
import com.fidegresa.report.ReportDtos.TransactionRow;

import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

/** CSV en UTF-8 con BOM y separador ";" para que Excel muestre bien las tildes. */
final class CsvReportWriter implements ReportWriter {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final String FORMULA_START = "=+-@\t\r\n";

    private final StringBuilder content = new StringBuilder("﻿");

    CsvReportWriter() {
        appendLine(HEADERS);
    }

    @Override
    public void write(TransactionRow row) {
        appendLine(List.of(
                DATE.format(row.createdAt().atZone(DateRange.LIMA)),
                safeText(row.branchName()), safeText(row.customerName()), safeText(row.programName()), safeText(row.typeLabel()),
                safeText(row.rewardName()), row.quantity().toPlainString(), MovementLabels.unit(row.unit()), row.amount().toPlainString(),
                safeText(row.cashierName())));
    }

    @Override
    public byte[] finish() {
        return content.toString().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public void close() {
        // No usa recursos externos.
    }

    /** Un texto que empieza como fórmula se antepone con ' para que Excel no lo ejecute. */
    static String safeText(String value) {
        if (value == null || value.isEmpty()) return "";
        return FORMULA_START.indexOf(value.charAt(0)) >= 0 || FORMULA_START.indexOf(value.stripLeading().isEmpty() ? ' ' : value.stripLeading().charAt(0)) >= 0
                ? "'" + value : value;
    }

    private void appendLine(List<String> cells) {
        content.append(cells.stream().map(CsvReportWriter::quote).collect(Collectors.joining(";"))).append("\r\n");
    }

    private static String quote(String value) {
        boolean needsQuotes = value.contains(";") || value.contains("\"") || value.contains("\n") || value.contains("\r");
        return needsQuotes ? "\"" + value.replace("\"", "\"\"") + "\"" : value;
    }
}
