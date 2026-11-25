package com.fidegresa.report;

import com.fidegresa.common.ApiException;
import com.fidegresa.common.DateRange;
import com.fidegresa.common.PageResponse;
import com.fidegresa.model.Branch;
import com.fidegresa.model.CardTransaction;
import com.fidegresa.model.LoyaltyProgram;
import com.fidegresa.model.TransactionType;
import com.fidegresa.report.ReportDtos.ExportFile;
import com.fidegresa.report.ReportDtos.RewardRow;
import com.fidegresa.report.ReportDtos.RewardsReport;
import com.fidegresa.report.ReportDtos.TransactionRow;
import com.fidegresa.repository.BranchRepository;
import com.fidegresa.repository.LoyaltyProgramRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Reportes para ventas, logística y contabilidad. No incluye movimientos anulados ni consultas. */
@Service
public class ReportService {
    static final int MAX_EXPORT_ROWS = 50_000;
    private static final int EXPORT_PAGE = 1_000;
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "createdAt", "id");

    private final ReportTransactionRepository transactions;
    private final BranchRepository branches;
    private final LoyaltyProgramRepository programs;
    private final EntityManager entityManager;

    ReportService(ReportTransactionRepository transactions, BranchRepository branches, LoyaltyProgramRepository programs, EntityManager entityManager) {
        this.transactions = transactions;
        this.branches = branches;
        this.programs = programs;
        this.entityManager = entityManager;
    }

    /** Filtros tal como llegan en la URL. */
    public record Filters(String from, String to, Long branchId, Long programId, String type) {
    }

    @Transactional(readOnly = true)
    public PageResponse<TransactionRow> transactions(Long businessId, Filters filters, Integer page, Integer size) {
        Pageable pageable = PageRequest.of(Math.max(0, page == null ? 0 : page), Math.min(100, Math.max(1, size == null ? 10 : size)), NEWEST_FIRST);
        return PageResponse.from(transactions.findAll(matching(businessId, range(filters), filters), pageable), ReportService::toRow);
    }

    @Transactional(readOnly = true)
    public ExportFile export(Long businessId, Filters filters, String format) {
        String extension = format == null || format.isBlank() ? "csv" : format.trim().toLowerCase(Locale.ROOT);
        if (!extension.equals("csv") && !extension.equals("xlsx")) throw ApiException.badRequest("El formato debe ser csv o xlsx.");
        DateRange range = range(filters);
        Specification<CardTransaction> specification = matching(businessId, range, filters);
        if (transactions.count(specification) > MAX_EXPORT_ROWS) {
            throw ApiException.unprocessable("El reporte supera las " + MAX_EXPORT_ROWS + " filas. Elige un rango de fechas más corto.");
        }
        String filename = "fidegresa-movimientos-" + range.from() + "-a-" + range.to() + "." + extension;
        String contentType = extension.equals("csv") ? "text/csv;charset=UTF-8" : "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        try (ReportWriter writer = extension.equals("csv") ? new CsvReportWriter() : new XlsxReportWriter()) {
            writeAll(specification, writer);
            return new ExportFile(filename, contentType, writer.finish());
        } catch (IOException ex) {
            throw new UncheckedIOException("No se pudo generar el reporte", ex);
        }
    }

    /** Premios canjeados por programa y local, con su costo: cantidad × rewardCost actual. */
    @Transactional(readOnly = true)
    public RewardsReport rewards(Long businessId, String from, String to, Long branchId, Integer page, Integer size) {
        DateRange range = DateRange.parse(from, to);
        requireBranch(businessId, branchId);
        Map<Long, String> branchNames = branches.findByBusinessIdOrderByIdAsc(businessId).stream().collect(Collectors.toMap(Branch::getId, Branch::getName));
        Map<Long, LoyaltyProgram> programsById = programs.findByBusinessIdOrderByCreatedAtDesc(businessId).stream()
                .collect(Collectors.toMap(LoyaltyProgram::getId, Function.identity()));
        List<RewardRow> rows = transactions.countRedemptions(businessId, range.start(), range.end()).stream()
                .filter(row -> branchId == null || branchId.equals(row[1]))
                .map(row -> toReward(row[0] == null ? null : programsById.get((Long) row[0]), (Long) row[1], branchNames.get((Long) row[1]), (Long) row[2]))
                .sorted(Comparator.comparingLong(RewardRow::redemptions).reversed()
                        .thenComparing(RewardRow::rewardName).thenComparing(RewardRow::branchName))
                .toList();
        BigDecimal totalCost = rows.stream().map(RewardRow::totalCost).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new RewardsReport(range.from(), range.to(), rows.stream().mapToLong(RewardRow::redemptions).sum(), MovementLabels.money(totalCost),
                rows.stream().anyMatch(RewardRow::missingRewardCost), PageResponse.of(rows, page, size));
    }

    private void writeAll(Specification<CardTransaction> specification, ReportWriter writer) throws IOException {
        Pageable pageable = PageRequest.of(0, EXPORT_PAGE, NEWEST_FIRST);
        Page<CardTransaction> page;
        do {
            page = transactions.findAll(specification, pageable);
            for (CardTransaction tx : page.getContent()) writer.write(toRow(tx));
            entityManager.clear(); // libera las filas ya escritas
            pageable = pageable.next();
        } while (page.hasNext());
    }

    private static DateRange range(Filters filters) {
        return DateRange.parse(filters.from(), filters.to());
    }

    private Specification<CardTransaction> matching(Long businessId, DateRange range, Filters filters) {
        requireBranch(businessId, filters.branchId());
        if (filters.programId() != null && programs.findByIdAndBusinessId(filters.programId(), businessId).isEmpty()) {
            throw ApiException.notFound("No encontramos el programa seleccionado.");
        }
        TransactionType type = type(filters.type());
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>(List.of(
                    cb.equal(root.get("business").get("id"), businessId),
                    cb.isFalse(root.get("voided")),
                    cb.notEqual(root.get("type"), TransactionType.CONSULT),
                    cb.greaterThanOrEqualTo(root.get("createdAt"), range.start()),
                    cb.lessThan(root.get("createdAt"), range.end())));
            if (filters.branchId() != null) predicates.add(cb.equal(root.get("branch").get("id"), filters.branchId()));
            if (filters.programId() != null) predicates.add(cb.equal(root.get("card").get("program").get("id"), filters.programId()));
            if (type != null) predicates.add(cb.equal(root.get("type"), type));
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    private void requireBranch(Long businessId, Long branchId) {
        if (branchId != null && branches.findByIdAndBusinessId(branchId, businessId).isEmpty()) {
            throw ApiException.notFound("No encontramos el local seleccionado.");
        }
    }

    private static TransactionType type(String value) {
        if (value == null || value.isBlank() || value.equalsIgnoreCase("all")) return null;
        try {
            TransactionType type = TransactionType.valueOf(value.trim().toUpperCase(Locale.ROOT));
            if (type != TransactionType.CONSULT) return type;
        } catch (IllegalArgumentException ignored) {
            // se informa abajo con un mensaje claro
        }
        throw ApiException.badRequest("El tipo debe ser VISIT, STAMP, REDEMPTION, PURCHASE, BALANCE_CHARGE, DISCOUNT o REFERRAL_BONUS.");
    }

    private static TransactionRow toRow(CardTransaction tx) {
        LoyaltyProgram program = MovementLabels.programOf(tx);
        return new TransactionRow(tx.getId(), tx.getCreatedAt(), tx.getBranch().getId(), tx.getBranch().getName(),
                tx.getCustomer().getId(), tx.getCustomer().getFullName(),
                program == null ? null : program.getId(), program == null ? null : program.getName(), program == null ? null : program.getType(),
                tx.getType().name(), MovementLabels.type(tx.getType(), program),
                tx.getType() == TransactionType.REDEMPTION ? MovementLabels.reward(program) : null,
                MovementLabels.quantity(tx), tx.getUnit(), MovementLabels.money(tx.getPurchaseAmount()),
                tx.getPerformedBy() == null ? null : tx.getPerformedBy().getFullName());
    }

    private static RewardRow toReward(LoyaltyProgram program, Long branchId, String branchName, long redemptions) {
        BigDecimal unitCost = program == null ? null : program.getRewardCost();
        BigDecimal totalCost = unitCost == null ? null : MovementLabels.money(unitCost.multiply(BigDecimal.valueOf(redemptions)));
        return new RewardRow(program == null ? null : program.getId(), program == null ? null : program.getName(), MovementLabels.reward(program),
                branchId, branchName, redemptions, unitCost, totalCost, unitCost == null);
    }
}
