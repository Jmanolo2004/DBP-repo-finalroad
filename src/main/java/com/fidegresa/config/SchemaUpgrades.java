package com.fidegresa.config;

import jakarta.annotation.PostConstruct;
import jakarta.persistence.EntityManagerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * Ajustes que ddl-auto=update no hace solo. Corre una vez al iniciar, después de que Hibernate actualiza las tablas
 * (por eso recibe el EntityManagerFactory) y antes de atender peticiones. Cada cambio se puede repetir sin efecto.
 */
@Component
public class SchemaUpgrades {
    private static final Logger log = LoggerFactory.getLogger(SchemaUpgrades.class);

    private final DataSource dataSource;

    public SchemaUpgrades(DataSource dataSource, EntityManagerFactory ignoredUntilSchemaIsUpdated) {
        this.dataSource = dataSource;
    }

    @PostConstruct
    void upgrade() {
        if (!postgres()) return;
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        // Hibernate creó un CHECK con los tipos de movimiento que existían; sin quitarlo, DISCOUNT no se puede guardar.
        // El tipo lo valida el enum de Java, así que no se vuelve a crear.
        jdbc.execute("alter table if exists card_transactions drop constraint if exists card_transactions_type_check");
    }

    private boolean postgres() {
        try (Connection connection = dataSource.getConnection()) {
            return connection.getMetaData().getDatabaseProductName().toLowerCase().contains("postgres");
        } catch (SQLException e) {
            log.warn("No se pudo revisar la base de datos para los ajustes del esquema: {}", e.getMessage());
            return false;
        }
    }
}
