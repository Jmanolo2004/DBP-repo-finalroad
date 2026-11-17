package com.fidegresa.data;

import com.fidegresa.model.Business;
import com.fidegresa.repository.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Carga en una sola transacción todos los datos de un negocio para calcular respuestas del panel. */
@Component
public class BusinessDataLoader {
    private final BranchRepository branches;
    private final CustomerRepository customers;
    private final CardTransactionRepository transactions;
    private final LoyaltyCardRepository cards;
    private final LoyaltyProgramRepository programs;
    private final ReviewRepository reviews;

    public BusinessDataLoader(BranchRepository branches, CustomerRepository customers, CardTransactionRepository transactions,
                              LoyaltyCardRepository cards, LoyaltyProgramRepository programs, ReviewRepository reviews) {
        this.branches = branches;
        this.customers = customers;
        this.transactions = transactions;
        this.cards = cards;
        this.programs = programs;
        this.reviews = reviews;
    }

    @Transactional(readOnly = true)
    public BusinessData load(Business business) {
        Long id = business.getId();
        return new BusinessData(business,
                branches.findByBusinessIdOrderByIdAsc(id),
                customers.findByBusinessId(id),
                transactions.findByBusinessIdAndVoidedFalse(id),
                cards.findByProgramBusinessId(id),
                programs.findByBusinessIdOrderByCreatedAtDesc(id),
                reviews.findByBusinessIdOrderByCreatedAtDesc(id));
    }
}
