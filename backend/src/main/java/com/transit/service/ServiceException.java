package com.transit.service;

/** 业务规则冲突（带中文提示，直接映射为 409/400） */
public class ServiceException extends RuntimeException {
    public final int status;

    public ServiceException(String message) {
        this(400, message);
    }

    public ServiceException(int status, String message) {
        super(message);
        this.status = status;
    }
}
