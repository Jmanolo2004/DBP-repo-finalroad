package com.fidegresa.metrics;

import com.fidegresa.metrics.ProfitabilityCalculator.Movement;
import com.fidegresa.metrics.ProfitabilityCalculator.Program;
import com.fidegresa.metrics.ProfitabilityCalculator.ProgramRow;
import com.fidegresa.metrics.ProfitabilityCalculator.Result;
import com.fidegresa.metrics.ProfitabilityCalculator.Summary;
import com.fidegresa.model.TransactionType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static com.fidegresa.model.TransactionType.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Casos que se pueden comprobar a mano con las definiciones del panel de rentabilidad. */
class ProfitabilityCalculatorTest {
    private static final long LUCIA = 1;
    private static final long MATEO = 2;
    private static final long PLAZA = 10;
    private static final long DULCE = 20;
    private static final long COFFEE = 100;
    private static final long CASHBACK = 200;
    private static final long GIFT = 300;
    private static final long NO_COSTS = 400;
    private static final long DELETED = 999;

    private final ProfitabilityCalculator calculator = new ProfitabilityCalculator(List.of(
            new Program(COFFEE, "Un café con cariño", "Estampillas", new BigDecimal("4.50"), new BigDecimal("18.00")),
            new Program(CASHBACK, "Cashback de la Plaza", "Cashback", null, null),
            new Program(GIFT, "Un regalo de la Plaza", "Giftcard", null, null),
            new Program(NO_COSTS, "Sellos sin configurar", "Estampillas", null, null)));

    @Test
    void sinMovimientosTodoEsCero() {
        Result result = calculate();
        assertSummary(result.total(), 0, "0.00", "0.00", null);
        assertThat(result.byProgram()).hasSize(4);
        assertThat(result.byBranch()).hasSize(2);
    }

    @Test
    void sinCanjesElRetornoEsNulo() {
        Result result = calculate(move(LUCIA, PLAZA, COFFEE, STAMP, "20.00"), move(LUCIA, PLAZA, COFFEE, STAMP, "30.00"));
        assertSummary(result.total(), 1, "50.00", "0.00", null);
    }

    @Test
    void sinVentasElRetornoEsCero() {
        Result result = calculate(move(LUCIA, PLAZA, COFFEE, REDEMPTION, "0.00"));
        assertSummary(result.total(), 0, "0.00", "4.50", "0.00");
    }

    @Test
    void soloSumaVentasDeClientesConDosOMasVisitas() {
        Result result = calculate(
                move(LUCIA, PLAZA, CASHBACK, PURCHASE, "40.00"),
                move(LUCIA, PLAZA, CASHBACK, PURCHASE, "60.00"),
                move(MATEO, PLAZA, CASHBACK, PURCHASE, "500.00"));
        assertSummary(result.total(), 1, "100.00", "0.00", null);
    }

    @Test
    void lasConsultasYLosCanjesNoCuentanComoVisita() {
        Result result = calculate(
                move(LUCIA, PLAZA, COFFEE, STAMP, "20.00"),
                move(LUCIA, PLAZA, COFFEE, CONSULT, "0.00"),
                move(LUCIA, PLAZA, COFFEE, REDEMPTION, "0.00"));
        assertSummary(result.total(), 0, "0.00", "4.50", "0.00");
    }

    @Test
    void enEstampillasSinMontoUsaElTicketPromedio() {
        Result result = calculate(
                move(LUCIA, PLAZA, COFFEE, STAMP, "0.00"),
                move(LUCIA, PLAZA, COFFEE, STAMP, null),
                move(LUCIA, PLAZA, COFFEE, STAMP, "25.00"),
                move(LUCIA, PLAZA, COFFEE, REDEMPTION, "0.00"));
        // 18 + 18 + 25 = 61; 61 ÷ 4.50 = 13.56
        assertSummary(result.total(), 1, "61.00", "4.50", "13.56");
    }

    @Test
    void elCashbackUsadoEsCostoYNoVenta() {
        Result result = calculate(
                move(LUCIA, PLAZA, CASHBACK, PURCHASE, "40.00"),
                move(LUCIA, PLAZA, CASHBACK, PURCHASE, "60.00"),
                move(LUCIA, PLAZA, CASHBACK, BALANCE_CHARGE, "8.00"));
        assertSummary(result.total(), 1, "100.00", "8.00", "12.50");
    }

    @Test
    void elPagoConGiftCardEsVentaYNoCosto() {
        Result result = calculate(move(LUCIA, PLAZA, GIFT, BALANCE_CHARGE, "30.00"), move(LUCIA, PLAZA, GIFT, BALANCE_CHARGE, "15.50"));
        assertSummary(result.total(), 1, "45.50", "0.00", null);
    }

    @Test
    void variosLocalesSumanElTotal() {
        // Lucía es recurrente por sus dos visitas, aunque solo tenga una en cada local.
        Result result = calculate(
                move(LUCIA, PLAZA, COFFEE, STAMP, "20.00"),
                move(LUCIA, DULCE, CASHBACK, PURCHASE, "50.00"),
                move(MATEO, DULCE, COFFEE, REDEMPTION, "0.00"));
        assertSummary(result.total(), 1, "70.00", "4.50", "15.56");
        assertSummary(result.byBranch().get(0).summary(), 1, "20.00", "0.00", null);
        assertSummary(result.byBranch().get(1).summary(), 1, "50.00", "4.50", "11.11");
        assertSummary(row(result, COFFEE).summary(), 1, "20.00", "4.50", "4.44");
        assertSummary(row(result, CASHBACK).summary(), 1, "50.00", "0.00", null);
        assertThat(sum(result.byProgram().stream().map(ProgramRow::summary).map(Summary::recurringSales).toList())).isEqualByComparingTo("70.00");
        assertThat(sum(result.byBranch().stream().map(branch -> branch.summary().recurringSales()).toList())).isEqualByComparingTo("70.00");
    }

    @Test
    void agrupaLoQueYaNoTieneProgramaYMantieneElTotal() {
        Result result = calculate(move(LUCIA, PLAZA, null, VISIT, "12.00"), move(LUCIA, PLAZA, DELETED, PURCHASE, "8.00"));
        assertSummary(result.total(), 1, "20.00", "0.00", null);
        assertThat(result.byProgram()).hasSize(5);
        ProgramRow last = result.byProgram().get(4);
        assertThat(last.program()).isNull();
        assertSummary(last.summary(), 1, "20.00", "0.00", null);
    }

    @Test
    void avisaSiFaltanDatosSoloEnEstampillas() {
        Result result = calculate(
                move(LUCIA, PLAZA, NO_COSTS, STAMP, null),
                move(LUCIA, PLAZA, NO_COSTS, STAMP, null),
                move(LUCIA, PLAZA, NO_COSTS, REDEMPTION, null));
        assertSummary(result.total(), 1, "0.00", "0.00", null);
        assertThat(row(result, NO_COSTS).program().missingRewardCost()).isTrue();
        assertThat(row(result, NO_COSTS).program().missingAverageTicket()).isTrue();
        assertThat(row(result, COFFEE).program().missingRewardCost()).isFalse();
        assertThat(row(result, CASHBACK).program().missingRewardCost()).isFalse();
    }

    @Test
    void unCostoDeCeroEsUnValorValido() {
        Program free = new Program(1L, "Gratis", "Estampillas", BigDecimal.ZERO, BigDecimal.ZERO);
        assertThat(free.missingRewardCost()).isFalse();
        assertThat(free.missingAverageTicket()).isFalse();
    }

    @Test
    void comparaConElPeriodoAnterior() {
        // Mes en curso: los mismos días del mes anterior.
        assertPrevious("2026-11-01", "2026-11-24", "2026-10-01", "2026-10-24");
        // Mes completo: el mes anterior completo, aunque dure distinto.
        assertPrevious("2026-11-01", "2026-11-30", "2026-10-01", "2026-10-31");
        assertPrevious("2026-03-01", "2026-03-30", "2026-02-01", "2026-02-28");
        assertPrevious("2026-01-01", "2026-01-15", "2025-12-01", "2025-12-15");
        // Otros rangos: la misma cantidad de días, justo antes.
        assertPrevious("2026-11-10", "2026-11-16", "2026-11-03", "2026-11-09");
        assertPrevious("2026-11-24", "2026-11-24", "2026-11-23", "2026-11-23");
    }

    private Result calculate(Movement... movements) {
        return calculator.calculate(List.of(movements), List.of(PLAZA, DULCE));
    }

    private static Movement move(long customer, long branch, Long program, TransactionType type, String amount) {
        return new Movement(customer, branch, program, type, amount == null ? null : new BigDecimal(amount));
    }

    private static ProgramRow row(Result result, long programId) {
        return result.byProgram().stream().filter(row -> row.program() != null && row.program().id() == programId).findFirst().orElseThrow();
    }

    private static BigDecimal sum(List<BigDecimal> values) {
        return values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static void assertSummary(Summary summary, int customers, String sales, String cost, String ratio) {
        assertThat(summary).isEqualTo(new Summary(customers, new BigDecimal(sales), new BigDecimal(cost), ratio == null ? null : new BigDecimal(ratio)));
    }

    private static void assertPrevious(String from, String to, String previousFrom, String previousTo) {
        assertThat(new ProfitabilityPeriod(LocalDate.parse(from), LocalDate.parse(to)).previous())
                .isEqualTo(new ProfitabilityPeriod(LocalDate.parse(previousFrom), LocalDate.parse(previousTo)));
    }
}
