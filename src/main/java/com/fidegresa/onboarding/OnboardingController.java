package com.fidegresa.onboarding;

import com.fidegresa.onboarding.OnboardingService.OnboardingResponse;
import com.fidegresa.security.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** "Tu progreso" del panel. branchId opcional: el local activo. */
@RestController
@RequestMapping("/api/v1/onboarding")
public class OnboardingController {
    private final OnboardingService service;
    private final CurrentUser currentUser;

    public OnboardingController(OnboardingService service, CurrentUser currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    @GetMapping("/progress")
    public OnboardingResponse progress(@RequestParam(required = false) Long branchId) {
        return service.progress(currentUser.businessId(), branchId);
    }
}
