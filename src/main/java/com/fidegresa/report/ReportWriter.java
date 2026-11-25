package com.fidegresa.report;

import com.fidegresa.report.ReportDtos.TransactionRow;

import java.io.IOException;
import java.util.List;

/** Escribe el reporte fila por fila para no tener todos los movimientos en memoria. */
interface ReportWriter extends AutoCloseable {
    List<String> HEADERS = List.of("Fecha y hora", "Local", "Cliente", "Programa", "Tipo", "Premio", "Cantidad", "Unidad", "Monto (S/)", "Cajero");

    void write(TransactionRow row) throws IOException;

    byte[] finish() throws IOException;

    @Override
    void close() throws IOException;
}
