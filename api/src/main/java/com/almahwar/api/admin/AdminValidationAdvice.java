package com.almahwar.api.admin;

import com.almahwar.api.error.ApiError;
import com.almahwar.api.error.ErrorCode;
import com.almahwar.api.web.RequestIdFilter;
import com.almahwar.service.ValidationException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Core messages may interpolate request text; expose field names with a safe fixed message instead. */
@Order(-2)
@RestControllerAdvice(assignableTypes=AdminController.class)
public class AdminValidationAdvice {
    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<ApiError> invalid(ValidationException exception,HttpServletRequest request) {
        var fields=exception.getErrors().keySet().stream().map(field->new ApiError.FieldError(field,"قيمة غير صالحة أو عملية غير مسموح بها.")).toList();
        return ResponseEntity.badRequest().body(ApiError.of(ErrorCode.VALIDATION_ERROR,null,request.getRequestURI(),RequestIdFilter.current(request),fields));
    }
}
