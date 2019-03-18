package com.psiphon3.psicash.util;

/**
 * Loading, Content, Error status to define the state asynchronous operation.
 */
public enum LceStatus {
    /**
     * Request has succeeded and response contains data
     */
    SUCCESS,
    /**
     * Request failed
     */
    FAILURE,
    /**
     * Request is sent. Waiting for a response
     */
    IN_FLIGHT
}
