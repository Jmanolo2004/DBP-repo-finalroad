package com.fidegresa.metrics;

import com.fidegresa.common.ApiException;
import com.fidegresa.common.DateRange;
import com.fidegresa.metrics.ProfitabilityCalculator.Movement;
import com.fidegresa.metrics.ProfitabilityCalculator.Program;
import com.fidegresa.metrics.ProfitabilityCalculator.ProgramRow;
import com.fidegresa.metrics.ProfitabilityCalculator.Result;
import com.fidegresa.metrics.ProfitabilityCalculator.Summary;
import com.fidegresa.model.Branch;
import com.fidegresa.model.LoyaltyProgram;
import com.fidegresa.model.TransactionType;
import com.fidegresa.repository.BranchRepository;
import com.fidegresa.repository.LoyaltyProgramRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Rentabilidad del negocio: cuánto gastaron los clientes recurrentes y cuánto costaron los premios. */
@Service
public class ProfitabilityService {
    private final MetricsTransactionRepository transactions;
    private final LoyaltyProgramRepository programs;
    private final BranchRepository branches;

    ProfitabilityService(MetricsTransactionRepository transactions, LoyaltyProgramRepository programs, BranchRepository branches) {
        this.transactions = transactions;
        this.programs = programs;
        this.branches = branches;
    }

    @Transactional(readOnly = true)
    public ProfitabilityResponse profitability(Long businessId, String from, String to, Long branchId) {
        ProfitabilityPeriod period = period(from, to);
        ProfitabilityPeriod previous = period.previous();
        List<Branch> selectedBranches = branchId == null
                ? branches.findByBusinessIdOrderByIdAsc(businessId)
                : List.of(branches.findByIdAndBusinessId(branchId, businessId)
                        .orElseThrow(() -> ApiException.notFound("No encontramos el local seleccionado.")));
        Map<Long, LoyaltyProgram> programsById = programs.findByBusinessIdOrderByCreatedAtDesc(businessId).stream()
                .collect(Collectors.toMap(LoyaltyProgram::getId, Function.identity()));
        ProfitabilityCalculator calculator = new ProfitabilityCalculator(programsById.values().stream()
                .map(program -> new Program(program.getId(), program.getName(), program.getType(), program.getRewardCost(), program.getAverageTicket()))
                .toList());
        List<Long> branchIds = selectedBranches.stream().map(Branch::getId).toList();
        Result current = calculator.calculate(movements(businessId, period, branchId), branchIds);
        Summary before = calculator.calculate(movements(businessId, previous, branchId), List.of()).total();
        Summary total = current.total();

        return new ProfitabilityResponse(period.from(), period.to(), total.recurringCustomers(), total.recurringSales(), total.rewardsCost(),
                total.returnRatio(),
                new ProfitabilityResponse.PreviousPeriod(previous.from(), previous.to(), before.recurringCustomers(), before.recurringSales(),
                        before.rewardsCost(), before.returnRatio()),
                programItems(current.byProgram(), programsById, branchId),
                branchItems(current.byBranch(), selectedBranches));
    }

    private static ProfitabilityPeriod period(String from, String to) {
        DateRange range = DateRange.parse(from, to);
        return new ProfitabilityPeriod(range.from(), range.to());
    }

    private List<Movement> movements(Long businessId, ProfitabilityPeriod period, Long branchId) {
        return transactions.findMovements(businessId, MetricsSupport.start(period.from()), MetricsSupport.start(period.to().plusDays(1))).stream()
                .map(row -> new Movement((Long) row[0], (Long) row[1], (Long) row[2], (TransactionType) row[3], (BigDecimal) row[4]))
                .filter(movement -> branchId == null || branchId.equals(movement.branchId()))
                .toList();
    }

    /** Muestra los programas del local elegido y los que tuvieron actividad; los borradores sin actividad se omiten. */
    private static List<ProfitabilityResponse.ProgramItem> programItems(Collection<ProgramRow> rows, Map<Long, LoyaltyProgram> programsById,
                                                                        Long branchId) {
        return rows.stream()
                .filter(row -> row.program() == null || row.summary().hasActivity() || isListed(programsById.get(row.program().id()), branchId))
                .sorted(Comparator.comparing((ProgramRow row) -> row.program() == null)
                        .thenComparing(row -> row.summary().recurringSales(), Comparator.reverseOrder())
                        .thenComparing(row -> row.program() == null ? "" : row.program().name()))
                .map(row -> {
                    Program program = row.program();
                    Summary summary = row.summary();
                    return new ProfitabilityResponse.ProgramItem(
                            program == null ? null : program.id(),
                            program == null ? "Sin programa" : program.name(),
                            program == null ? null : program.type(),
                            program == null ? null : programsById.get(program.id()).getBranch().getName(),
                            summary.recurringCustomers(), summary.recurringSales(), summary.rewardsCost(), summary.returnRatio(),
                            program != null && program.missingRewardCost(), program != null && program.missingAverageTicket());
                })
                .toList();
    }

    private static boolean isListed(LoyaltyProgram program, Long branchId) {
        return !"Borrador".equals(program.getStatus()) && (branchId == null || branchId.equals(program.getBranch().getId()));
    }

    private static List<ProfitabilityResponse.BranchItem> branchItems(List<ProfitabilityCalculator.BranchRow> rows, List<Branch> branches) {
        Map<Long, String> names = branches.stream().collect(Collectors.toMap(Branch::getId, Branch::getName));
        return rows.stream()
                .sorted(Comparator.comparing((ProfitabilityCalculator.BranchRow row) -> row.summary().recurringSales(), Comparator.reverseOrder()))
                .map(row -> new ProfitabilityResponse.BranchItem(row.branchId(), names.get(row.branchId()), row.summary().recurringCustomers(),
                        row.summary().recurringSales(), row.summary().rewardsCost(), row.summary().returnRatio()))
                .toList();
    }
}
