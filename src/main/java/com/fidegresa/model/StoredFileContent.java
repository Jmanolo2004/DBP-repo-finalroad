package com.fidegresa.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Contenido de un archivo cuando se guarda en la base de datos (FILE_STORAGE=db).
 * Solo existe para que Hibernate cree la tabla: se lee y escribe por JDBC, en partes, para no cargar
 * archivos de hasta 20 MB enteros en memoria. Es bytea en PostgreSQL (sin @Lob, que usaría oid).
 */
@Entity
@Table(name = "stored_file_contents")
@Getter
@Setter
@NoArgsConstructor
public class StoredFileContent {
    @Id
    @Column(name = "file_id")
    private Long fileId;

    @JdbcTypeCode(SqlTypes.VARBINARY)
    @Column(name = "data", nullable = false, columnDefinition = "bytea")
    private byte[] data;
}
