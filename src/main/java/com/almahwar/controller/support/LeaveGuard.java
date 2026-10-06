package com.almahwar.controller.support;

/**
 * A page with work that must not be lost when the user navigates away (today: the POS cart).
 * Registered with {@link Navigator#setLeaveGuard}; every navigation path goes through
 * {@link Navigator#leave(Runnable)}, so no path can skip it.
 */
public interface LeaveGuard {

    /** There is something that would be lost by leaving now. */
    boolean hasUnsavedWork();

    /**
     * Lets the user decide (complete, hold as draft, stay, or clear). {@code leave} is run only once the work is
     * saved or deliberately discarded; staying simply never runs it.
     */
    void askToLeave(Runnable leave);

    /**
     * The session is ending without the user (inactivity timeout): keep the work safe if possible (e.g. hold the
     * cart as a draft), then run {@code then} whatever happened.
     */
    void saveBeforeForcedExit(Runnable then);
}
