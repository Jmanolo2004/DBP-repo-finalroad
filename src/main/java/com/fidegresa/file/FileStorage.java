package com.fidegresa.file;

import java.io.IOException;
import java.io.InputStream;

/** Dónde se guarda el contenido de los archivos: la base de datos (db) o un almacenamiento compatible con S3 (s3). */
public interface FileStorage {

    /** Nombre corto que se guarda en stored_files.storage. */
    String name();

    /**
     * Guarda el contenido. Se lee una sola vez del flujo, sin cargarlo entero en memoria.
     * @param sha256 huella hexadecimal del contenido (S3 la exige para firmar).
     */
    void put(String key, InputStream content, long size, String contentType, String sha256) throws IOException;

    /** Abre el contenido para enviarlo al navegador. Quien lo abre debe cerrarlo. */
    InputStream open(String key) throws IOException;

    void delete(String key) throws IOException;
}
