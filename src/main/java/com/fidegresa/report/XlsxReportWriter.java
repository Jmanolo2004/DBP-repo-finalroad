package com.fidegresa.report;

import com.fidegresa.common.DateRange;
import com.fidegresa.report.ReportDtos.TransactionRow;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/** Excel (.xlsx). Los textos se guardan como texto, así que nunca se ejecutan como fórmula; fechas y montos son valores reales. */
final class XlsxReportWriter implements ReportWriter {
    private static final int ROWS_IN_MEMORY = 100;
    private static final int[] WIDTHS = {18, 24, 26, 28, 20, 24, 10, 10, 14, 24};

    private final SXSSFWorkbook workbook = new SXSSFWorkbook(ROWS_IN_MEMORY);
    private final Sheet sheet = workbook.createSheet("Movimientos");
    private final CellStyle dateStyle;
    private final CellStyle moneyStyle;
    private int nextRow;

    XlsxReportWriter() {
        dateStyle = style("dd/mm/yyyy hh:mm");
        moneyStyle = style("#,##0.00");
        Font bold = workbook.createFont();
        bold.setBold(true);
        CellStyle header = workbook.createCellStyle();
        header.setFont(bold);
        Row row = sheet.createRow(nextRow++);
        for (int column = 0; column < HEADERS.size(); column++) {
            Cell cell = row.createCell(column);
            cell.setCellValue(HEADERS.get(column));
            cell.setCellStyle(header);
            sheet.setColumnWidth(column, WIDTHS[column] * 256);
        }
        sheet.createFreezePane(0, 1);
    }

    @Override
    public void write(TransactionRow data) {
        Row row = sheet.createRow(nextRow++);
        Cell date = row.createCell(0);
        date.setCellValue(data.createdAt().atZone(DateRange.LIMA).toLocalDateTime());
        date.setCellStyle(dateStyle);
        text(row, 1, data.branchName());
        text(row, 2, data.customerName());
        text(row, 3, data.programName());
        text(row, 4, data.typeLabel());
        text(row, 5, data.rewardName());
        Cell quantity = row.createCell(6);
        quantity.setCellValue(data.quantity().doubleValue());
        if ("SOLES".equals(data.unit())) quantity.setCellStyle(moneyStyle);
        text(row, 7, MovementLabels.unit(data.unit()));
        Cell amount = row.createCell(8);
        amount.setCellValue(data.amount().doubleValue());
        amount.setCellStyle(moneyStyle);
        text(row, 9, data.cashierName());
    }

    @Override
    public byte[] finish() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        workbook.write(output);
        return output.toByteArray();
    }

    /** Borra también los archivos temporales. */
    @Override
    public void close() throws IOException {
        workbook.close();
    }

    private CellStyle style(String format) {
        CellStyle style = workbook.createCellStyle();
        style.setDataFormat(workbook.createDataFormat().getFormat(format));
        return style;
    }

    private static void text(Row row, int column, String value) {
        row.createCell(column).setCellValue(value == null ? "" : value);
    }
}
