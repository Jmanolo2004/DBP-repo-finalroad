package com.fidegresa.file;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * Guarda el contenido en la tabla stored_file_contents (bytea en PostgreSQL). Es la opción por defecto.
 * Escribe con un flujo y lee por partes de 1 MB, para no tener un PDF de 20 MB entero en memoria.
 * La clave es el id del archivo.
 */
@Component
@ConditionalOnProperty(name = "app.files.storage", havingValue = "db", matchIfMissing = true)
public class DatabaseFileStorage implements FileStorage {
    static final int CHUNK_BYTES = 1024 * 1024;

    private final JdbcTemplate jdbc;

    public DatabaseFileStorage(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String name() {
        return "db";
    }

    @Override
    public void put(String key, InputStream content, long size, String contentType, String sha256) {
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("insert into stored_file_contents (file_id, data) values (?, ?)");
            statement.setLong(1, Long.parseLong(key));
            statement.setBinaryStream(2, content, size);
            return statement;
        });
    }

    @Override
    public InputStream open(String key) throws IOException {
        long fileId = Long.parseLong(key);
        List<Long> sizes = jdbc.queryForList("select octet_length(data) from stored_file_contents where file_id = ?", Long.class, fileId);
        if (sizes.isEmpty()) throw new IOException("No existe el contenido del archivo " + key);
        return new ChunkedInputStream(fileId, sizes.get(0));
    }

    @Override
    public void delete(String key) {
        jdbc.update("delete from stored_file_contents where file_id = ?", Long.parseLong(key));
    }

    /** Pide el contenido a la base en partes (substring), a medida que el navegador lo descarga. */
    private final class ChunkedInputStream extends InputStream {
        private final long fileId;
        private final long size;
        private long offset;
        private byte[] buffer = new byte[0];
        private int position;

        private ChunkedInputStream(long fileId, long size) {
            this.fileId = fileId;
            this.size = size;
        }

        private boolean fill() {
            if (position < buffer.length) return true;
            if (offset >= size) return false;
            int length = (int) Math.min(CHUNK_BYTES, size - offset);
            // En SQL las posiciones empiezan en 1.
            buffer = jdbc.queryForObject("select substring(data from ? for ?) from stored_file_contents where file_id = ?",
                    byte[].class, (int) (offset + 1), length, fileId); // int: PostgreSQL no tiene substring(bytea, bigint, int)
            if (buffer == null || buffer.length == 0) return false;
            offset += buffer.length;
            position = 0;
            return true;
        }

        @Override
        public int read() {
            return fill() ? buffer[position++] & 0xFF : -1;
        }

        @Override
        public int read(byte[] target, int off, int len) {
            if (len == 0) return 0;
            if (!fill()) return -1;
            int count = Math.min(len, buffer.length - position);
            System.arraycopy(buffer, position, target, off, count);
            position += count;
            return count;
        }
    }
}
