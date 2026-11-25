package com.fidegresa.referral;

import com.fidegresa.model.Business;
import com.fidegresa.model.Customer;
import com.fidegresa.repository.BusinessRepository;
import com.fidegresa.repository.CustomerRepository;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.text.Normalizer;
import java.util.Locale;

/** Códigos fáciles de leer y dictar: el primer nombre (o el negocio) sin tildes y dos o tres dígitos, ej. LUCIA23. */
@Component
public class ReferralCodes {
    private final CustomerRepository customers;
    private final BusinessRepository businesses;
    private final SecureRandom random = new SecureRandom();

    public ReferralCodes(CustomerRepository customers, BusinessRepository businesses) {
        this.customers = customers;
        this.businesses = businesses;
    }

    /** El código del cliente; si no tiene, lo crea (la entidad debe estar en una transacción de escritura). */
    public String ensure(Customer customer) {
        if (customer.getReferralCode() == null) {
            String base = base(customer.getFullName(), "AMIGO");
            String code;
            int attempt = 0;
            do {
                code = base + digits(attempt < 20 ? 2 : 4);
                attempt++;
            } while (customers.existsByReferralCode(code));
            customer.setReferralCode(code);
        }
        return customer.getReferralCode();
    }

    public String ensure(Business business) {
        if (business.getReferralCode() == null) {
            String base = base(business.getName(), "NEGOCIO");
            String code;
            int attempt = 0;
            do {
                code = base + digits(attempt < 20 ? 3 : 5);
                attempt++;
            } while (businesses.existsByReferralCode(code));
            business.setReferralCode(code);
        }
        return business.getReferralCode();
    }

    /** Normaliza lo que escribe la persona: mayúsculas, sin espacios. null si no parece un código. */
    public static String clean(String code) {
        if (code == null) return null;
        String value = code.trim().toUpperCase(Locale.ROOT);
        return value.matches("[A-Z0-9]{3,20}") ? value : null;
    }

    static String base(String name, String fallback) {
        String first = name == null || name.isBlank() ? "" : name.trim().split("\\s+")[0];
        String plain = Normalizer.normalize(first, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toUpperCase(Locale.ROOT).replaceAll("[^A-Z]", "");
        if (plain.length() < 3) plain = fallback;
        return plain.length() > 10 ? plain.substring(0, 10) : plain;
    }

    private String digits(int count) {
        StringBuilder value = new StringBuilder();
        for (int i = 0; i < count; i++) value.append(random.nextInt(10));
        return value.toString();
    }
}
