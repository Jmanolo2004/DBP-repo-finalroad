package com.fidegresa.customer;

import com.fidegresa.common.ApiException;
import com.fidegresa.common.PageResponse;
import com.fidegresa.common.Texts;
import com.fidegresa.data.BusinessData;
import com.fidegresa.data.BusinessDataLoader;
import com.fidegresa.data.Views;
import com.fidegresa.model.Branch;
import com.fidegresa.model.Business;
import com.fidegresa.model.Customer;
import com.fidegresa.model.LoyaltyCard;
import com.fidegresa.program.CardIssuer;
import com.fidegresa.program.ProgramViews;
import com.fidegresa.repository.BranchRepository;
import com.fidegresa.repository.CustomerRepository;
import com.fidegresa.repository.LoyaltyCardRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

@Service
public class CustomerService {
    private static final Map<String, Comparator<Views.CustomerView>> SORTERS = Map.of(
            "recent", Comparator.comparing(Views.CustomerView::ultimaVisita).reversed(),
            "name", Comparator.comparing(Views.CustomerView::nombre, java.text.Collator.getInstance(new Locale("es"))),
            "visits", Comparator.comparingInt(Views.CustomerView::visitas).reversed());

    private final BusinessDataLoader loader;
    private final CustomerRepository customers;
    private final BranchRepository branches;
    private final LoyaltyCardRepository cards;
    private final CardIssuer cardIssuer;

    public CustomerService(BusinessDataLoader loader, CustomerRepository customers, BranchRepository branches,
                           LoyaltyCardRepository cards, CardIssuer cardIssuer) {
        this.loader = loader;
        this.customers = customers;
        this.branches = branches;
        this.cards = cards;
        this.cardIssuer = cardIssuer;
    }

    /** Lista paginada con búsqueda (nombre, correo, teléfono, local, distrito), filtro de estado y orden. */
    @Transactional(readOnly = true)
    public PageResponse<Views.CustomerView> page(Business business, String search, String status, String sort, Integer page, Integer size) {
        BusinessData data = loader.load(business);
        String query = Texts.normalize(search == null ? "" : search.trim());
        String phoneQuery = query.replaceAll("\\D", "");
        String wantedStatus = status == null || status.isBlank() ? "Todos" : status;
        List<Views.CustomerView> results = data.clientes.stream()
                .filter(customer -> wantedStatus.equals("Todos") || customer.estado().equals(wantedStatus))
                .filter(customer -> {
                    if (query.isEmpty()) return true;
                    String searchable = Texts.normalize(String.join(" ", customer.nombre(), customer.correo(), customer.telefono(),
                            customer.tarjeta(), customer.distrito(), customer.localNombre()));
                    String phone = String.valueOf(customer.telefono()).replaceAll("\\D", "");
                    return searchable.contains(query) || (phoneQuery.length() >= 3 && phone.contains(phoneQuery));
                })
                .sorted(SORTERS.getOrDefault(sort, SORTERS.get("recent")))
                .toList();
        return PageResponse.of(results, page, size);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> stats(Business business) {
        BusinessData data = loader.load(business);
        int totalVisits = data.clientes.stream().mapToInt(Views.CustomerView::visitas).sum();
        long active = data.clientes.stream().filter(customer -> customer.estado().equals("Activa")).count();
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("total", data.clientes.size());
        stats.put("active", active);
        stats.put("totalVisits", totalVisits);
        stats.put("averageVisits", totalVisits / (double) Math.max(1, data.clientes.size()));
        return stats;
    }

    /** Detalle: datos del cliente, sus visitas recientes, premios, su tarjeta principal, todas sus tarjetas y reseñas. */
    @Transactional(readOnly = true)
    public Map<String, Object> detail(Business business, Long id) {
        BusinessData data = loader.load(business);
        Views.CustomerView customer = data.customer(id).orElseThrow(() -> ApiException.notFound("No encontramos a este cliente."));
        List<Views.VisitView> visits = data.visitas.stream()
                .filter(visit -> visit.clienteId().equals(id))
                .sorted(Comparator.comparing(Views.VisitView::fecha).reversed())
                .toList();
        List<LoyaltyCard> ownCards = data.cardEntities.stream()
                .filter(card -> card.getCustomer().getId().equals(id))
                .sorted(Comparator.comparing(LoyaltyCard::getId))
                .toList();

        Map<String, Object> detail = new LinkedHashMap<>(toMap(customer));
        detail.put("totalGastado", BusinessData.round2(visits.stream().mapToDouble(Views.VisitView::monto).sum()));
        detail.put("visitasRecientes", visits.stream().limit(8).toList());
        detail.put("premiosCanjeados", visits.stream().filter(Views.VisitView::premioCanjeado).count());
        detail.put("tarjetaPrograma", ownCards.isEmpty() ? null : ProgramViews.base(ownCards.get(0).getProgram()));
        detail.put("tarjetas", ownCards.stream().map(card -> {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("id", card.getId());
            view.put("qrToken", card.getQrToken());
            view.put("estado", card.getStatus().name());
            view.put("programaId", card.getProgram().getId());
            view.put("programa", card.getProgram().getName());
            view.put("tipo", card.getProgram().getType());
            view.put("estampillas", card.getStamps());
            view.put("saldoCashback", card.getCashbackBalance());
            view.put("puntos", card.getPoints());
            view.put("saldoGiftcard", card.getGiftBalance());
            view.put("venceEn", card.getExpiresAt());
            return view;
        }).toList());
        detail.put("resenas", data.resenas.stream().filter(review -> review.clienteId().equals(id)).toList());
        return detail;
    }

    /** Registra un cliente en un local y le entrega las tarjetas de los programas activos de ese local. */
    @Transactional
    public Views.CustomerView create(Business business, Map<String, Object> body) {
        String fullName = Texts.limit(Texts.required(body.get("fullName"), "El nombre es obligatorio"), 120);
        String email = Texts.required(body.get("email"), "El email es obligatorio").toLowerCase(Locale.ROOT);
        if (!Texts.EMAIL.matcher(email).matches()) throw ApiException.badRequest("Debe ser un formato de email válido");
        if (customers.existsByBusinessIdAndEmailIgnoreCase(business.getId(), email)) {
            throw ApiException.conflict("Ya existe un cliente con este correo.");
        }
        List<Branch> locals = branches.findByBusinessIdOrderByIdAsc(business.getId());
        if (locals.isEmpty()) throw ApiException.badRequest("Primero crea un local para registrar clientes.");
        Long localId = body.get("localId") == null ? null : Long.valueOf(String.valueOf(body.get("localId")));
        Branch branch = locals.stream().filter(local -> local.getId().equals(localId)).findFirst().orElse(locals.get(0));

        Customer customer = new Customer();
        customer.setBusiness(business);
        customer.setBranch(branch);
        customer.setFullName(fullName);
        customer.setEmail(email);
        customer.setPhone(Texts.optional(body.get("phone"), 30));
        customer.setLatitude(branch.getLatitude());
        customer.setLongitude(branch.getLongitude());
        customer.setMapX(Math.min(955, branch.getMapX() + 30));
        customer.setMapY(Math.max(45, branch.getMapY() - 20));
        customer.setRegisteredAt(Instant.now());
        customers.save(customer);
        cardIssuer.issueForCustomer(customer);
        customers.flush();
        return loader.load(business).customer(customer.getId()).orElseThrow();
    }

    private static Map<String, Object> toMap(Views.CustomerView customer) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (var component : Views.CustomerView.class.getRecordComponents()) {
            try {
                map.put(component.getName(), component.getAccessor().invoke(customer));
            } catch (ReflectiveOperationException ex) {
                throw new IllegalStateException(ex);
            }
        }
        return map;
    }
}
