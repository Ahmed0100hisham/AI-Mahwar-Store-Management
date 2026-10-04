package com.almahwar.service;

/**
 * Health of the backend the client talks to — SQL Server today, the REST API
 * later. Lets the UI show a connection indicator without knowing which one.
 */
public interface SystemStatusService {

    /** {@code true} if the backend answers. May block for a few seconds. */
    boolean isBackendReachable();

    /** Short, safe description for display, e.g. {@code "localhost / AlMahwarDB"} (never credentials). */
    String backendDescription();
}
