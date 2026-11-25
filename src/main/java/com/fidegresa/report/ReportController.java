package com.fidegresa.report;

import com.fidegresa.common.PageResponse;
import com.fidegresa.report.ReportDtos.ExportFile;
import com.fidegresa.report.ReportDtos.RewardsReport;
import com.fidegresa.report.ReportDtos.TransactionRow;
import com.fidegresa.security.CurrentUser;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Reportes del negocio de la sesión; solo para cuentas administradoras. */
@RestController
@RequestMapping("/api/v1/reports")
public class ReportController {
    private final ReportService service;
    private final CurrentUser currentUser;

    public ReportController(ReportService service, CurrentUser currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    @GetMapping("/transactions")
    public PageResponse<TransactionRow> transactions(@RequestParam(required = false) String from, @RequestParam(required = false) String to,
                                                     @RequestParam(required = false) Long branchId, @RequestParam(required = false) Long programId,
                                                     @RequestParam(required = false) String type, @RequestParam(required = false) Integer page,
                                                     @RequestParam(required = false) Integer size) {
        return service.transactions(businessId(), new ReportService.Filters(from, to, branchId, programId, type), page, size);
    }

    @GetMapping("/transactions/export")
    public ResponseEntity<byte[]> export(@RequestParam(required = false) String from, @RequestParam(required = false) String to,
                                         @RequestParam(required = false) Long branchId, @RequestParam(required = false) Long programId,
                                         @RequestParam(required = false) String type, @RequestParam(required = false) String format) {
        ExportFile file = service.export(businessId(), new ReportService.Filters(from, to, branchId, programId, type), format);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(file.filename()).build().toString())
                .body(file.content());
    }

    @GetMapping("/rewards")
    public RewardsReport rewards(@RequestParam(required = false) String from, @RequestParam(required = false) String to,
                                 @RequestParam(required = false) Long branchId, @RequestParam(required = false) Integer page,
                                 @RequestParam(required = false) Integer size) {
        return service.rewards(businessId(), from, to, branchId, page, size);
    }

    private Long businessId() {
        return currentUser.admin().getBusiness().getId();
    }
}
