package com.fidegresa.publicmenu;

import com.fidegresa.model.Branch;
import com.fidegresa.repository.BranchRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Identificador público de cada local para su menú (/m/{slug}), por ejemplo "heladeria-dulce-barranco".
 * Se asigna una sola vez: al arrancar a los locales que no lo tengan y, para los nuevos, la primera vez que
 * el dueño pide su enlace. No cambia si se renombra el local, para que los QR impresos sigan funcionando.
 */
@Component
public class PublicSlugs {
    private static final Logger log = LoggerFactory.getLogger(PublicSlugs.class);
    private static final int MAX = 70;

    private final BranchRepository branches;

    public PublicSlugs(BranchRepository branches) {
        this.branches = branches;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void fillMissing() {
        int filled = 0;
        for (Branch branch : branches.findByPublicSlugIsNull()) {
            assign(branch);
            filled++;
        }
        if (filled > 0) log.info("Se asignó el enlace público a {} local(es).", filled);
    }

    /** Devuelve el slug del local, creándolo si todavía no tiene. */
    @Transactional(propagation = Propagation.REQUIRED)
    public String ensure(Branch branch) {
        if (branch.getPublicSlug() == null) assign(branch);
        return branch.getPublicSlug();
    }

    private void assign(Branch branch) {
        String base = slugify(branch.getBusiness().getName() + " " + branch.getName());
        String candidate = base;
        for (int n = 2; branches.existsByPublicSlug(candidate); n++) {
            String suffix = "-" + n;
            candidate = base.substring(0, Math.min(base.length(), MAX - suffix.length())) + suffix;
        }
        branch.setPublicSlug(candidate);
        branches.saveAndFlush(branch);
    }

    /** Minúsculas, sin tildes ni símbolos, palabras unidas con guiones. */
    static String slugify(String text) {
        String plain = Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .replace('ñ', 'n').replace('Ñ', 'n').toLowerCase(Locale.ROOT);
        String slug = plain.replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (slug.length() > MAX) slug = slug.substring(0, MAX).replaceAll("-+$", "");
        return slug.isEmpty() ? "local" : slug;
    }
}
