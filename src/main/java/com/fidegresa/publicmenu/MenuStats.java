package com.fidegresa.publicmenu;

import com.fidegresa.model.Branch;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Cuenta las vistas y los registros del menú público por local y día (hora de Lima).
 * Una vista cuenta una vez por visitante al día: el navegador envía un visitorId aleatorio y aquí solo se
 * recuerda su huella, en memoria (se pierde al reiniciar, lo que a lo sumo cuenta una vista de más).
 */
@Component
public class MenuStats {
    static final ZoneId LIMA = ZoneId.of("America/Lima");
    private static final Pattern VISITOR = Pattern.compile("^[A-Za-z0-9-]{8,64}$");
    private static final int REMEMBERED = 50_000;

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final Set<String> seen = Collections.newSetFromMap(Collections.synchronizedMap(new LinkedHashMap<>(1024, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > REMEMBERED;
        }
    }));

    @org.springframework.beans.factory.annotation.Autowired
    public MenuStats(JdbcTemplate jdbc) {
        this(jdbc, Clock.system(LIMA));
    }

    MenuStats(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Suma una vista, salvo que el mismo visitante ya haya visto hoy el menú de este local. */
    public void view(Branch branch, String visitorId) {
        LocalDate today = LocalDate.now(clock);
        if (visitorId != null && VISITOR.matcher(visitorId).matches() && !seen.add(hash(branch.getId() + "|" + today + "|" + visitorId))) return;
        add(branch, today, "views");
    }

    public void join(Branch branch) {
        add(branch, LocalDate.now(clock), "joins");
    }

    /** Suma 1 en una sola sentencia; si la fila del día no existe, la crea (y si otra petición ganó, vuelve a sumar). */
    private void add(Branch branch, LocalDate day, String column) {
        String update = "update menu_daily_stats set " + column + " = " + column + " + 1 where branch_id = ? and stat_day = ?";
        if (jdbc.update(update, branch.getId(), Date.valueOf(day)) > 0) return;
        try {
            jdbc.update("insert into menu_daily_stats (branch_id, business_id, stat_day, views, joins) values (?, ?, ?, ?, ?)",
                    branch.getId(), branch.getBusiness().getId(), Date.valueOf(day), column.equals("views") ? 1 : 0, column.equals("joins") ? 1 : 0);
        } catch (DuplicateKeyException race) {
            jdbc.update(update, branch.getId(), Date.valueOf(day));
        }
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)), 0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
