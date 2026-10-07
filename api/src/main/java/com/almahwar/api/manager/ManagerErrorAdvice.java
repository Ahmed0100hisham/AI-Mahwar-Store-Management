package com.almahwar.api.manager;

import com.almahwar.api.error.ApiError;
import com.almahwar.api.error.ErrorCode;
import com.almahwar.api.web.RequestIdFilter;
import com.almahwar.dao.DataAccessException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLTransientConnectionException;

/** Manager-only mapping for a connection lost after acquisition, wrapped by the immutable core DAO. */
@Order(-1)
@RestControllerAdvice(assignableTypes=ManagerController.class)
public class ManagerErrorAdvice {
    private static final Logger LOG=LoggerFactory.getLogger(ManagerErrorAdvice.class);
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ApiError> database(DataAccessException exception,HttpServletRequest request) {
        ErrorCode code=ErrorCode.INTERNAL_ERROR;
        for(Throwable cause=exception.getCause();cause!=null;cause=cause.getCause()) {
            if(cause instanceof SQLException sql && (sql instanceof SQLTransientConnectionException
                    || sql instanceof SQLNonTransientConnectionException
                    || sql.getSQLState()!=null && sql.getSQLState().startsWith("08"))) {
                code=ErrorCode.SERVICE_UNAVAILABLE;break;
            }
        }
        LOG.error("Manager database failure [{} {}] ({})",request.getMethod(),request.getRequestURI(),code);
        return ResponseEntity.status(code.status()).body(ApiError.of(code,null,request.getRequestURI(),RequestIdFilter.current(request),null));
    }
}
