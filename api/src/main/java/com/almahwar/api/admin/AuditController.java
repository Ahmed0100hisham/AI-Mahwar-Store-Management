package com.almahwar.api.admin;

import com.almahwar.api.manager.ManagerDateRange;
import com.almahwar.api.manager.ManagerPage;
import com.almahwar.api.web.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/manager/audit")
@SecurityRequirement(name="bearer")
@Tag(name="Manager audit")
public class AuditController {
    private final AuditQueryService service;
    public AuditController(AuditQueryService service) { this.service=service; }
    @GetMapping @PreAuthorize("@adminAccess.allowed('AUDIT_LOG')")
    @Operation(summary="Bounded audit events",description="AUDIT_LOG. Inclusive business dates, max 366 days; today default. page 0..10000, size 1..100, default 20. Fixed date,desc and unique id tie-breaker. Positive userId, released action and category filters. No q, free-form descriptions, raw JSON, machine metadata or credential details. Unknown historical event/category values become OTHER. No-store.")
    public PageResponse<AdminDtos.Audit> audit(@ParameterObject @ModelAttribute ManagerDateRange.Input range,
            @ParameterObject @ModelAttribute ManagerPage page,@RequestParam(required=false) Integer userId,
            @RequestParam(required=false) String action,@RequestParam(required=false) String category) {
        return service.audit(range,page,userId,action,category);
    }
}
