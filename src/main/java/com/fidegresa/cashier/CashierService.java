package com.fidegresa.cashier;

import com.fidegresa.cashier.CashierDtos.*;
import com.fidegresa.common.ApiException;
import com.fidegresa.common.PageResponse;
import com.fidegresa.model.*;
import com.fidegresa.repository.BranchRepository;
import com.fidegresa.security.CurrentUser;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Operaciones del cajero sobre las tarjetas. Cada movimiento:
 * bloquea la fila de la tarjeta (dos escaneos seguidos no se aplican dos veces),
 * respeta la llave de idempotencia, valida las reglas del programa y deja un CardTransaction.
 */
@Service
public class CashierService {
    static final ZoneId LIMA = ZoneId.of("America/Lima");
    /** Ventana para anular un movimiento (la app muestra "Anular" solo mientras canBeVoided sea true). */
    static final Duration VOID_WINDOW = Duration.ofMinutes(10);
    static final List<TransactionType> CASHIER_TYPES =
            List.of(TransactionType.STAMP, TransactionType.REDEMPTION, TransactionType.PURCHASE, TransactionType.BALANCE_CHARGE);
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("99999.99");
    private static final int MAX_STAMPS_PER_OPERATION = 50;
    private static final Pattern TOKEN = Pattern.compile("^[A-Za-z0-9_-]{4,120}$");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final CashierCardRepository cards;
    private final CashierTransactionRepository transactions;
    private final BranchRepository branches;
    private final CurrentUser currentUser;
    private final CashierMapper mapper;

    public CashierService(CashierCardRepository cards, CashierTransactionRepository transactions, BranchRepository branches,
                          CurrentUser currentUser, CashierMapper mapper) {
        this.cards = cards;
        this.transactions = transactions;
        this.branches = branches;
        this.currentUser = currentUser;
        this.mapper = mapper;
    }

    // ------------------------------------------------------------------ lectura

    @Transactional(readOnly = true)
    public CardDto findByQr(String rawToken) {
        String token = cleanToken(rawToken);
        Long businessId = currentUser.businessId();
        Optional<LoyaltyCard> card = token == null ? Optional.empty() : cards.findFullByQrToken(token, businessId);
        // El cajero puede escribir solo el código impreso ("EST-7KQ2MA"), sin el prefijo FDG-.
        if (card.isEmpty() && token != null && !token.toUpperCase(Locale.ROOT).startsWith("FDG-")) {
            card = cards.findFullByQrToken("FDG-" + token, businessId);
        }
        return mapper.toCard(card.orElseThrow(() -> ApiException.notFound(
                "No encontramos una tarjeta con el código " + (token == null ? "escaneado" : token.toUpperCase(Locale.ROOT)) + " en tu negocio.")));
    }

    @Transactional(readOnly = true)
    public PageResponse<TransactionDto> cardTransactions(Long cardId, Integer page, Integer size, String sort) {
        LoyaltyCard card = cards.findFullByIdAndBusinessId(cardId, currentUser.businessId()).orElseThrow(CashierService::cardNotFound);
        return toPage(transactions.pageByCard(card.getId(), CASHIER_TYPES, pageable(page, size, sort)));
    }

    @Transactional(readOnly = true)
    public PageResponse<TransactionDto> businessTransactions(Long branchId, Integer page, Integer size, String sort) {
        Long businessId = currentUser.businessId();
        Pageable pageable = pageable(page, size, sort);
        if (branchId == null) return toPage(transactions.pageByBusiness(businessId, CASHIER_TYPES, pageable));
        branches.findByIdAndBusinessId(branchId, businessId).orElseThrow(() -> ApiException.notFound("No encontramos el local seleccionado."));
        return toPage(transactions.pageByBranch(businessId, branchId, CASHIER_TYPES, pageable));
    }

    // ------------------------------------------------------------------ movimientos

    @Transactional
    public CardDto addStamps(Long cardId, StampRequest request, String idempotencyKey) {
        StampRequest body = request == null ? new StampRequest(null, null, null) : request;
        int quantity = body.quantity() == null ? 1 : body.quantity();
        if (quantity < 1 || quantity > MAX_STAMPS_PER_OPERATION) throw ApiException.badRequest("La cantidad de sellos debe ser entre 1 y " + MAX_STAMPS_PER_OPERATION + ".");
        BigDecimal amount = body.amount() == null ? null : validAmount(body.amount());

        Operation op = begin(cardId, idempotencyKey, TransactionType.STAMP);
        if (op.replay()) return mapper.toCard(op.card());
        LoyaltyCard card = op.card();
        ProgramRules rules = requireType(card, ProgramRules.STAMPS, "Esta tarjeta no es de estampillas.");
        Branch branch = branch(body.branchId());

        int goal = rules.stampGoal();
        if (card.getStamps() >= goal) {
            throw ApiException.conflict("La tarjeta ya está completa. Canjea el premio antes de sumar más sellos.");
        }
        int missing = goal - card.getStamps();
        if (quantity > missing) {
            throw ApiException.unprocessable(missing == 1 ? "Solo le falta 1 sello para completar la tarjeta." : "Solo le faltan " + missing + " sellos para completar la tarjeta.");
        }
        int limit = rules.dailyStampLimit();
        if (limit > 0) {
            int today = sumSince(card, TransactionType.STAMP, startOfToday()).intValue();
            if (today + quantity > limit) {
                String rule = limit == 1 ? "1 sello por día" : limit + " sellos por día";
                throw ApiException.unprocessable(today == 0
                        ? "Este programa permite " + rule + "."
                        : "Esta tarjeta ya recibió " + (today == 1 ? "1 sello" : today + " sellos") + " hoy. El límite es " + rule + ".");
            }
        }
        if (rules.stampByMinimumPurchase() && amount != null && amount.compareTo(rules.minimumPurchase()) < 0) {
            throw ApiException.unprocessable("La compra debe ser de al menos " + soles(rules.minimumPurchase()) + " para ganar un sello.");
        }

        card.setStamps(card.getStamps() + quantity);
        record(op, branch, TransactionType.STAMP, amount, BigDecimal.valueOf(quantity), "STAMPS", false);
        return mapper.toCard(card);
    }

    @Transactional
    public CardDto redeem(Long cardId, RedemptionRequest request, String idempotencyKey) {
        RedemptionRequest body = request == null ? new RedemptionRequest(null, null) : request;
        Operation op = begin(cardId, idempotencyKey, TransactionType.REDEMPTION);
        if (op.replay()) return mapper.toCard(op.card());
        LoyaltyCard card = op.card();
        ProgramRules rules = requireType(card, ProgramRules.STAMPS, "Esta tarjeta no tiene premios por estampillas.");
        Branch branch = branch(body.branchId());
        if (body.rewardId() != null && !body.rewardId().equals(card.getProgram().getId())) {
            throw ApiException.notFound("No encontramos ese premio en el programa de la tarjeta.");
        }
        int goal = rules.stampGoal();
        if (card.getStamps() < goal) {
            int missing = goal - card.getStamps();
            throw ApiException.conflict(missing == 1 ? "Le falta 1 sello para canjear el premio." : "Le faltan " + missing + " sellos para canjear el premio.");
        }
        card.setStamps(Math.max(0, card.getStamps() - goal));
        record(op, branch, TransactionType.REDEMPTION, null, BigDecimal.valueOf(goal), "STAMPS", true);
        return mapper.toCard(card);
    }

    @Transactional
    public CardDto purchase(Long cardId, AmountRequest request, String idempotencyKey) {
        BigDecimal amount = validAmount(request == null ? null : request.amount());
        Operation op = begin(cardId, idempotencyKey, TransactionType.PURCHASE);
        if (op.replay()) return mapper.toCard(op.card());
        LoyaltyCard card = op.card();
        String type = card.getProgram().getType();
        ProgramRules rules = new ProgramRules(card.getProgram());

        if (!ProgramRules.CASHBACK.equals(type) && !ProgramRules.LEVELS.equals(type)) {
            throw ApiException.unprocessable("Las compras solo suman saldo en programas de cashback o de niveles.");
        }
        Branch branch = branch(request.branchId());

        if (ProgramRules.CASHBACK.equals(type)) {
            BigDecimal earned = amount.multiply(rules.cashbackPercent()).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
            BigDecimal cap = rules.monthlyCap();
            if (cap != null) {
                BigDecimal earnedThisMonth = sumSince(card, TransactionType.PURCHASE, startOfMonth());
                earned = earned.min(cap.subtract(earnedThisMonth).max(BigDecimal.ZERO)).setScale(2, RoundingMode.HALF_UP);
            }
            card.setCashbackBalance(CashierMapper.money(card.getCashbackBalance()).add(earned));
            record(op, branch, TransactionType.PURCHASE, amount, earned, "SOLES", false);
        } else {
            int points = amount.multiply(rules.pointsPerSol()).setScale(0, RoundingMode.FLOOR).intValueExact();
            card.setPoints(card.getPoints() + points);
            record(op, branch, TransactionType.PURCHASE, amount, BigDecimal.valueOf(points), "POINTS", false);
        }
        return mapper.toCard(card);
    }

    @Transactional
    public CardDto chargeBalance(Long cardId, AmountRequest request, String idempotencyKey) {
        BigDecimal amount = validAmount(request == null ? null : request.amount());
        Operation op = begin(cardId, idempotencyKey, TransactionType.BALANCE_CHARGE);
        if (op.replay()) return mapper.toCard(op.card());
        LoyaltyCard card = op.card();
        String type = card.getProgram().getType();
        ProgramRules rules = new ProgramRules(card.getProgram());

        if (!ProgramRules.CASHBACK.equals(type) && !ProgramRules.GIFT.equals(type)) {
            throw ApiException.unprocessable("Esta tarjeta no tiene saldo para cobrar.");
        }
        Branch branch = branch(request.branchId());

        if (ProgramRules.CASHBACK.equals(type)) {
            BigDecimal balance = CashierMapper.money(card.getCashbackBalance());
            BigDecimal minimum = rules.minimumRedeemBalance();
            if (balance.compareTo(minimum) < 0) {
                throw ApiException.conflict("El cliente necesita al menos " + soles(minimum) + " de saldo para usar su cashback. Tiene " + soles(balance) + ".");
            }
            if (amount.compareTo(balance) > 0) {
                throw ApiException.conflict("Saldo insuficiente: el cliente tiene " + soles(balance) + " de cashback.");
            }
            card.setCashbackBalance(balance.subtract(amount));
        } else {
            BigDecimal balance = CashierMapper.money(card.getGiftBalance());
            if (amount.compareTo(balance) > 0) {
                throw ApiException.conflict("Saldo insuficiente: la gift card tiene " + soles(balance) + ".");
            }
            card.setGiftBalance(balance.subtract(amount));
        }
        record(op, branch, TransactionType.BALANCE_CHARGE, amount, amount, "SOLES", false);
        return mapper.toCard(card);
    }

    @Transactional
    public CardDto updateStatus(Long cardId, StatusRequest request) {
        String value = request == null || request.status() == null ? "" : request.status().trim().toUpperCase(Locale.ROOT);
        CardStatus status;
        try {
            status = CardStatus.valueOf(value);
        } catch (IllegalArgumentException ex) {
            throw ApiException.badRequest("El estado debe ser ACTIVE o BLOCKED.");
        }
        LoyaltyCard card = lockCard(cardId);
        card.setStatus(status);
        return mapper.toCard(card);
    }

    /** Anula el último movimiento de la tarjeta si tiene menos de 10 minutos, y revierte su efecto. */
    @Transactional
    public void voidTransaction(Long transactionId) {
        Long businessId = currentUser.businessId();
        CardTransaction tx = transactions.lockById(transactionId)
                .filter(found -> found.getBusiness().getId().equals(businessId))
                .orElseThrow(() -> ApiException.notFound("No encontramos el movimiento."));
        if (tx.isVoided()) return; // ya anulado: repetir el DELETE no cambia nada
        if (tx.getCard() == null || !CASHIER_TYPES.contains(tx.getType())) {
            throw ApiException.unprocessable("Este movimiento no se puede anular.");
        }
        if (tx.getCreatedAt().isBefore(Instant.now().minus(VOID_WINDOW))) {
            throw ApiException.unprocessable("Solo se pueden anular movimientos de los últimos " + VOID_WINDOW.toMinutes() + " minutos.");
        }
        LoyaltyCard card = lockCard(tx.getCard().getId());
        Long latest = latestIds(List.of(card.getId())).get(card.getId());
        if (!tx.getId().equals(latest)) {
            throw ApiException.conflict("Solo se puede anular el último movimiento de la tarjeta.");
        }
        revert(card, tx);
        tx.setVoided(true);
    }

    private void revert(LoyaltyCard card, CardTransaction tx) {
        String type = card.getProgram().getType();
        BigDecimal units = tx.getUnits() == null ? BigDecimal.ZERO : tx.getUnits();
        switch (tx.getType()) {
            case STAMP -> {
                if (card.getStamps() < units.intValue()) throw ApiException.conflict("Los sellos de este movimiento ya se usaron en un canje.");
                card.setStamps(card.getStamps() - units.intValue());
            }
            case REDEMPTION -> card.setStamps(card.getStamps() + units.intValue());
            case PURCHASE -> {
                if (ProgramRules.LEVELS.equals(type)) {
                    if (card.getPoints() < units.intValue()) throw ApiException.conflict("Los puntos de esta compra ya no están en la tarjeta.");
                    card.setPoints(card.getPoints() - units.intValue());
                } else {
                    BigDecimal balance = CashierMapper.money(card.getCashbackBalance());
                    if (balance.compareTo(units) < 0) throw ApiException.conflict("El cashback de esta compra ya se usó.");
                    card.setCashbackBalance(balance.subtract(units));
                }
            }
            case BALANCE_CHARGE -> {
                if (ProgramRules.GIFT.equals(type)) card.setGiftBalance(CashierMapper.money(card.getGiftBalance()).add(units));
                else card.setCashbackBalance(CashierMapper.money(card.getCashbackBalance()).add(units));
            }
            default -> throw ApiException.unprocessable("Este movimiento no se puede anular.");
        }
    }

    // ------------------------------------------------------------------ apoyo

    /** Tarjeta bloqueada + resultado de la llave de idempotencia. */
    private record Operation(LoyaltyCard card, String key, boolean replay) {
    }

    /**
     * Bloquea la tarjeta y revisa la llave de idempotencia ya con el bloqueo tomado,
     * así una segunda petición con la misma llave espera a la primera y luego ve su movimiento.
     */
    private Operation begin(Long cardId, String rawKey, TransactionType type) {
        String key = rawKey == null || rawKey.isBlank() ? null : rawKey.trim();
        if (key != null && key.length() > 64) throw ApiException.badRequest("La llave de idempotencia es demasiado larga (máximo 64 caracteres).");
        LoyaltyCard card = lockCard(cardId);
        if (key != null) {
            List<CardTransaction> previous = transactions.findByIdempotencyKey(currentUser.businessId(), key);
            if (!previous.isEmpty()) {
                CardTransaction first = previous.get(0);
                if (first.getCard() == null || !first.getCard().getId().equals(card.getId()) || first.getType() != type) {
                    throw ApiException.unprocessable("Esta llave de idempotencia ya se usó para otra operación.");
                }
                return new Operation(card, key, true);
            }
        }
        requireUsable(card);
        return new Operation(card, key, false);
    }

    private LoyaltyCard lockCard(Long cardId) {
        Long businessId = currentUser.businessId();
        if (cardId == null) throw cardNotFound();
        return cards.lockById(cardId)
                .filter(card -> card.getProgram().getBusiness().getId().equals(businessId))
                .orElseThrow(CashierService::cardNotFound);
    }

    private static void requireUsable(LoyaltyCard card) {
        if (card.getStatus() == CardStatus.BLOCKED) {
            throw ApiException.conflict("La tarjeta está bloqueada. Reactívala para registrar movimientos.");
        }
        if (card.getExpiresAt() != null && card.getExpiresAt().isBefore(Instant.now())) {
            throw ApiException.conflict("La tarjeta venció el " + DATE.format(card.getExpiresAt().atZone(LIMA)) + ".");
        }
        LoyaltyProgram program = card.getProgram();
        if (!"Activa".equalsIgnoreCase(program.getStatus())) {
            String state = "En pausa".equalsIgnoreCase(program.getStatus()) ? "está en pausa"
                    : "Borrador".equalsIgnoreCase(program.getStatus()) ? "todavía es un borrador" : "no está activo";
            throw ApiException.conflict("El programa \"" + program.getName() + "\" " + state + " y no acepta movimientos.");
        }
    }

    private static ProgramRules requireType(LoyaltyCard card, String type, String message) {
        if (!type.equals(card.getProgram().getType())) throw ApiException.unprocessable(message);
        return new ProgramRules(card.getProgram());
    }

    private Branch branch(Long branchId) {
        if (branchId == null) throw ApiException.badRequest("Elige el local donde estás antes de registrar movimientos.");
        Branch branch = branches.findByIdAndBusinessId(branchId, currentUser.businessId())
                .orElseThrow(() -> ApiException.notFound("No encontramos el local seleccionado."));
        if (!branch.isActive()) throw ApiException.unprocessable("El local " + branch.getName() + " está inactivo.");
        return branch;
    }

    private void record(Operation op, Branch branch, TransactionType type, BigDecimal purchaseAmount, BigDecimal units, String unit,
                        boolean rewardRedeemed) {
        LoyaltyCard card = op.card();
        AppUser user = currentUser.get();
        CardTransaction tx = new CardTransaction();
        tx.setType(type);
        tx.setPurchaseAmount(purchaseAmount == null ? BigDecimal.ZERO.setScale(2) : purchaseAmount);
        tx.setUnits(units);
        tx.setUnit(unit);
        tx.setRewardRedeemed(rewardRedeemed);
        tx.setCreatedAt(Instant.now());
        tx.setIdempotencyKey(op.key());
        tx.setCustomer(card.getCustomer());
        tx.setBranch(branch);
        tx.setCard(card);
        tx.setPerformedBy(user);
        tx.setBusiness(user.getBusiness());
        transactions.save(tx);
    }

    private static BigDecimal validAmount(BigDecimal amount) {
        if (amount == null) throw ApiException.badRequest("Ingresa un monto.");
        if (amount.signum() <= 0) throw ApiException.badRequest("El monto debe ser mayor a S/ 0.00.");
        if (amount.stripTrailingZeros().scale() > 2) throw ApiException.badRequest("Usa un monto con hasta 2 decimales.");
        if (amount.compareTo(MAX_AMOUNT) > 0) throw ApiException.badRequest("El monto es demasiado alto.");
        return amount.setScale(2, RoundingMode.UNNECESSARY);
    }

    private PageResponse<TransactionDto> toPage(Page<CardTransaction> page) {
        Set<Long> cardIds = new HashSet<>();
        page.getContent().forEach(tx -> cardIds.add(tx.getCard().getId()));
        Map<Long, Long> latest = latestIds(cardIds);
        Instant limit = Instant.now().minus(VOID_WINDOW);
        return PageResponse.from(page, tx -> mapper.toTransaction(tx,
                !tx.isVoided() && !tx.getCreatedAt().isBefore(limit) && tx.getId().equals(latest.get(tx.getCard().getId()))));
    }

    private Map<Long, Long> latestIds(Collection<Long> cardIds) {
        Map<Long, Long> latest = new HashMap<>();
        if (cardIds.isEmpty()) return latest;
        for (Object[] row : transactions.latestIdByCard(cardIds, CASHIER_TYPES)) latest.put((Long) row[0], (Long) row[1]);
        return latest;
    }

    private static Pageable pageable(Integer page, Integer size, String sort) {
        int number = Math.max(0, page == null ? 0 : page);
        int pageSize = Math.min(100, Math.max(1, size == null ? 10 : size));
        // Solo se ordena por fecha (sort=createdAt,desc|asc); el id desempata movimientos del mismo instante.
        Sort.Direction direction = sort != null && sort.toLowerCase(Locale.ROOT).endsWith(",asc") ? Sort.Direction.ASC : Sort.Direction.DESC;
        return PageRequest.of(number, pageSize, Sort.by(direction, "createdAt").and(Sort.by(direction, "id")));
    }

    /** Acepta el token solo o una URL que lo tenga al final, igual que src/utils/qrPayload.js de la app. */
    static String cleanToken(String raw) {
        if (raw == null) return null;
        String value = raw.trim().split("[?#]")[0].replaceAll("/+$", "");
        if (value.contains("/")) value = value.substring(value.lastIndexOf('/') + 1);
        return TOKEN.matcher(value).matches() ? value : null;
    }

    private BigDecimal sumSince(LoyaltyCard card, TransactionType type, Instant since) {
        BigDecimal sum = transactions.sumUnitsSince(card.getId(), type, since);
        return sum == null ? BigDecimal.ZERO : sum;
    }

    private static Instant startOfToday() {
        return LocalDate.now(LIMA).atStartOfDay(LIMA).toInstant();
    }

    private static Instant startOfMonth() {
        return LocalDate.now(LIMA).withDayOfMonth(1).atStartOfDay(LIMA).toInstant();
    }

    private static String soles(BigDecimal value) {
        return "S/ " + CashierMapper.money(value).toPlainString();
    }

    private static ApiException cardNotFound() {
        return ApiException.notFound("No encontramos la tarjeta en tu negocio.");
    }
}
