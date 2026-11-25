package com.fidegresa.metrics;

import com.fidegresa.model.TransactionType;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Cálculo puro de la rentabilidad (sin base de datos). Recibe los movimientos vigentes del periodo y local elegidos.
 *
 * Cliente recurrente: 2 o más visitas en el periodo. Una visita es VISIT, STAMP, PURCHASE o un pago con gift card;
 * las consultas, los canjes y el uso de cashback no cuentan, para no contar dos veces la misma visita.
 * Ventas: montos de los clientes recurrentes; en Estampillas sin monto se usa el ticket promedio del programa.
 * Costo de premios: canjes × costo del premio + cashback usado.
 * Retorno: ventas ÷ costo (null si no hubo costo).
 */
final class ProfitabilityCalculator {
    private static final String STAMPS = "Estampillas";
    private static final String CASHBACK = "Cashback";
    private static final String GIFT = "Giftcard";
    private static final int RECURRING_VISITS = 2;

    /** programId es null si el programa ya se eliminó o la visita no usó tarjeta. */
    record Movement(Long customerId, Long branchId, Long programId, TransactionType type, BigDecimal amount) {
    }

    record Program(Long id, String name, String type, BigDecimal rewardCost, BigDecimal averageTicket) {
        boolean missingRewardCost() {
            return STAMPS.equals(type) && rewardCost == null;
        }

        boolean missingAverageTicket() {
            return STAMPS.equals(type) && averageTicket == null;
        }
    }

    record Summary(int recurringCustomers, BigDecimal recurringSales, BigDecimal rewardsCost, BigDecimal returnRatio) {
        boolean hasActivity() {
            return recurringCustomers > 0 || recurringSales.signum() > 0 || rewardsCost.signum() > 0;
        }
    }

    /** program es null en la fila que agrupa los movimientos sin programa. */
    record ProgramRow(Program program, Summary summary) {
    }

    record BranchRow(Long branchId, Summary summary) {
    }

    record Result(Summary total, List<ProgramRow> byProgram, List<BranchRow> byBranch) {
    }

    private final Map<Long, Program> programs = new LinkedHashMap<>();

    ProfitabilityCalculator(Collection<Program> programs) {
        programs.forEach(program -> this.programs.put(program.id(), program));
    }

    /**
     * Los clientes recurrentes se definen sobre todos los movimientos recibidos. Por eso las ventas y los costos
     * de las filas suman el total; los clientes no, porque uno puede estar en varios programas o locales.
     */
    Result calculate(List<Movement> movements, Collection<Long> branchIds) {
        Set<Long> recurring = recurringCustomers(movements);

        Map<Long, List<Movement>> movementsByProgram = groupBy(movements, movement -> programOf(movement) == null ? null : movement.programId());
        List<ProgramRow> byProgram = new ArrayList<>();
        for (Program program : programs.values()) {
            byProgram.add(new ProgramRow(program, summarize(movementsByProgram.getOrDefault(program.id(), List.of()), recurring)));
        }
        Summary withoutProgram = summarize(movementsByProgram.getOrDefault(null, List.of()), recurring);
        if (withoutProgram.hasActivity()) byProgram.add(new ProgramRow(null, withoutProgram));

        Map<Long, List<Movement>> movementsByBranch = groupBy(movements, Movement::branchId);
        List<BranchRow> byBranch = branchIds.stream()
                .map(id -> new BranchRow(id, summarize(movementsByBranch.getOrDefault(id, List.of()), recurring)))
                .toList();

        return new Result(summarize(movements, recurring), byProgram, byBranch);
    }

    private Set<Long> recurringCustomers(List<Movement> movements) {
        return movements.stream().filter(this::isVisit)
                .collect(Collectors.groupingBy(Movement::customerId, Collectors.counting()))
                .entrySet().stream()
                .filter(entry -> entry.getValue() >= RECURRING_VISITS)
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
    }

    private Summary summarize(List<Movement> movements, Set<Long> recurring) {
        Set<Long> customers = new HashSet<>();
        BigDecimal sales = BigDecimal.ZERO;
        BigDecimal cost = BigDecimal.ZERO;
        for (Movement movement : movements) {
            if (isVisit(movement) && recurring.contains(movement.customerId())) {
                customers.add(movement.customerId());
                sales = sales.add(saleOf(movement));
            }
            cost = cost.add(costOf(movement));
        }
        sales = money(sales);
        cost = money(cost);
        BigDecimal ratio = cost.signum() == 0 ? null : sales.divide(cost, 2, RoundingMode.HALF_UP);
        return new Summary(customers.size(), sales, cost, ratio);
    }

    private boolean isVisit(Movement movement) {
        return switch (movement.type()) {
            case VISIT, STAMP, PURCHASE -> true;
            case BALANCE_CHARGE -> hasType(movement, GIFT);
            // El descuento acompaña una compra que ya se registra con su sello o compra: no se cuenta dos veces.
            case REDEMPTION, CONSULT, DISCOUNT -> false;
        };
    }

    private BigDecimal saleOf(Movement movement) {
        BigDecimal amount = amountOf(movement);
        if (movement.type() != TransactionType.STAMP || amount.signum() > 0) return amount;
        Program program = programOf(movement);
        return program == null || program.averageTicket() == null ? BigDecimal.ZERO : program.averageTicket();
    }

    private BigDecimal costOf(Movement movement) {
        if (movement.type() == TransactionType.REDEMPTION) {
            Program program = programOf(movement);
            return program == null || program.rewardCost() == null ? BigDecimal.ZERO : program.rewardCost();
        }
        // El descuento de una promoción es un costo (en DISCOUNT, amount es el descuento).
        if (movement.type() == TransactionType.DISCOUNT) return amountOf(movement);
        // El cashback usado es un costo, no una venta.
        if (movement.type() == TransactionType.BALANCE_CHARGE && hasType(movement, CASHBACK)) return amountOf(movement);
        return BigDecimal.ZERO;
    }

    private Program programOf(Movement movement) {
        return movement.programId() == null ? null : programs.get(movement.programId());
    }

    private boolean hasType(Movement movement, String type) {
        Program program = programOf(movement);
        return program != null && Objects.equals(program.type(), type);
    }

    private static BigDecimal amountOf(Movement movement) {
        return movement.amount() == null ? BigDecimal.ZERO : movement.amount();
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    /** Agrupa permitiendo la clave null (Collectors.groupingBy no la acepta). */
    private static Map<Long, List<Movement>> groupBy(List<Movement> movements, Function<Movement, Long> key) {
        Map<Long, List<Movement>> groups = new HashMap<>();
        movements.forEach(movement -> groups.computeIfAbsent(key.apply(movement), ignored -> new ArrayList<>()).add(movement));
        return groups;
    }
}
